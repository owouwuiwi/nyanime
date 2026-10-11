package it.lorelore.nyanime.novel

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * All network work is a source-owned port. The cache never interprets a site or owns credentials.
 */
interface NovelOnlineFetcher {
    suspend fun chapter(reference: String): NovelOnlineBody

    suspend fun image(reference: String): NovelOnlineImage
}

data class NovelOnlineImage(val bytes: ByteArray, val mediaType: String) {
    init {
        require(bytes.size in 1..8 * 1024 * 1024)
        require(mediaType in setOf("image/png", "image/jpeg", "image/webp", "image/gif"))
    }
}

/** Disposable content cache, independent of portable metadata, original EPUBs and downloads. */
class NovelOnlineCache(
    context: Context,
    private val root: File = File(context.cacheDir, "novel-online-v1"),
    databaseName: String = "novel_online_cache_v1.db",
    private val byteLimit: Long = 256L * 1024 * 1024,
    private val entryLimit: Int = 8192,
) : AutoCloseable {
    init {
        require(byteLimit >= 1024 && entryLimit in 1..16_384)
    }

    private val database = Database(context.applicationContext, databaseName)
    private val writes = Mutex()
    private val stamp = AtomicLong(0)
    private val pins = ConcurrentHashMap<Key, AtomicInteger>()
    private val keyed = mutableMapOf<Key, Lock>()
    private var initialized = false

    private data class Key(val owner: String, val edition: String, val href: String)

    private class Lock {
        val mutex = Mutex()
        var users = 0
    }

    private data class Entry(
        val key: Key,
        val hash: String,
        val path: String,
        val type: String,
        val bytes: Long,
        val bindings: Map<String, String>,
    )

    override fun close() = database.close()

    data class Usage(val bytes: Long, val entries: Int, val limitBytes: Long)

    suspend fun usage(): Usage =
        withContext(Dispatchers.IO) {
            writes.withLock {
                database.readableDatabase
                    .rawQuery("SELECT COALESCE(SUM(cost),0),COUNT(*) FROM entries", null)
                    .use {
                        it.moveToFirst()
                        Usage(it.getLong(0), it.getInt(1), byteLimit)
                    }
            }
        }

    suspend fun open(
        book: NovelBook,
        document: NovelOnlineDocument,
        fetcher: NovelOnlineFetcher,
    ): NovelOnlineAccess = openAccess(book, document, fetcher)

    suspend fun openCached(book: NovelBook, document: NovelOnlineDocument): NovelOnlineAccess =
        openAccess(book, document, null)

    private suspend fun openAccess(
        book: NovelBook,
        document: NovelOnlineDocument,
        fetcher: NovelOnlineFetcher?,
    ): NovelOnlineAccess =
        withContext(Dispatchers.IO) {
            require(
                book.id == document.bookId &&
                    book.origin == document.origin &&
                    book.format == NovelFormat.ONLINE,
            )
            val versions = writes.withLock {
                initialize()
                database.readableDatabase
                    .query(
                        "entries",
                        arrayOf("href", "hash"),
                        "owner=? AND edition=? AND href LIKE 'chapters/%'",
                        arrayOf(book.id, book.contentVersion),
                        null,
                        null,
                        null,
                    )
                    .use { rows ->
                        buildMap {
                            while (rows.moveToNext()) put(rows.getString(0), rows.getString(1))
                        }
                    }
            }
            Access(book, document, fetcher, versions)
        }

    private inner class Access(
        private val book: NovelBook,
        override val document: NovelOnlineDocument,
        private val fetcher: NovelOnlineFetcher?,
        initialVersions: Map<String, String>,
    ) : NovelOnlineAccess {
        private val chapters = document.chapters.associateBy { it.href }
        private val declaredImages = linkedMapOf<String, String>()
        private val versions = MutableStateFlow(initialVersions)
        override val chapterVersions = versions.asStateFlow()
        private val closed = AtomicBoolean(false)

        override fun contains(href: String): Boolean =
            !closed.get() &&
                (href in chapters || synchronized(declaredImages) { href in declaredImages })

        override suspend fun cachedOnly(): NovelOnlineAccess = openCached(book, document)

        override fun retain(href: String): AutoCloseable {
            val key = Key(book.id, book.contentVersion, href)
            pins.compute(key) { _, count -> (count ?: AtomicInteger()).apply { incrementAndGet() } }
            val released = AtomicBoolean(false)
            return AutoCloseable {
                if (!released.getAndSet(true)) {
                    pins.computeIfPresent(key) { _, count ->
                        if (count.decrementAndGet() == 0) null else count
                    }
                }
            }
        }

        override suspend fun materialize(href: String): NovelOnlineFile =
            withContext(Dispatchers.IO) {
                check(contains(href)) { "Undeclared chapter resource" }
                val key = Key(book.id, book.contentVersion, href)
                withKey(key) {
                    val existing = cached(key)
                    val entry =
                        existing
                            ?: run {
                                val fetcher =
                                    fetcher
                                        ?: throw IOException("Chapter is not cached on this device")
                                if (href in chapters) {
                                    versions.value = versions.value - href
                                    val chapter = chapters.getValue(href)
                                    val prepared =
                                        NovelOnlineHtml.prepare(
                                            chapter,
                                            fetcher.chapter(chapter.reference),
                                            document.chapters,
                                        )
                                    check(!closed.get())
                                    store(key, prepared.bytes, "text/html", prepared.resources)
                                } else {
                                    val reference =
                                        synchronized(declaredImages) { declaredImages[href] }
                                            ?: throw IOException(
                                                "Chapter image is no longer available",
                                            )
                                    val image = fetcher.image(reference)
                                    verifyImage(image)
                                    check(!closed.get())
                                    store(key, image.bytes, image.mediaType, emptyMap())
                                }
                            }
                    if (href in chapters) {
                        synchronized(declaredImages) {
                            entry.bindings.forEach { (image, reference) ->
                                declaredImages.remove(image)
                                declaredImages[image] = reference
                            }
                            // Readium retains only nearby pages. Old bindings can be reconstructed
                            // from a cached chapter instead of growing with an entire long novel.
                            while (declaredImages.size > 2048) {
                                declaredImages.remove(declaredImages.keys.first())
                            }
                        }
                        versions.value = versions.value + (href to entry.hash)
                    }
                    NovelOnlineFile(
                        owned(entry.path),
                        requireNotNull(MediaType(entry.type)),
                        entry.hash,
                    )
                }
            }

        override fun close() {
            closed.set(true)
        }
    }

    private suspend fun <T> withKey(key: Key, action: suspend () -> T): T {
        val lock = synchronized(keyed) { keyed.getOrPut(key, ::Lock).also { it.users++ } }
        try {
            return lock.mutex.withLock { action() }
        } finally {
            synchronized(keyed) { if (--lock.users == 0) keyed.remove(key) }
        }
    }

    private suspend fun cached(key: Key): Entry? = writes.withLock {
        val db = database.writableDatabase
        val entry =
            db.query(
                "entries",
                arrayOf("hash", "path", "mime", "bytes", "bindings"),
                "owner=? AND edition=? AND href=?",
                arrayOf(key.owner, key.edition, key.href),
                null,
                null,
                null,
            )
                .use { rows ->
                    if (!rows.moveToFirst()) {
                        null
                    } else {
                        Entry(
                            key,
                            rows.getString(0),
                            rows.getString(1),
                            rows.getString(2),
                            rows.getLong(3),
                            NovelJson.decodeFromString<Map<String, String>>(rows.getString(4)),
                        )
                    }
                } ?: return@withLock null
        val file = owned(entry.path)
        val valid =
            file.isFile &&
                file.length() == entry.bytes &&
                file.inputStream().use {
                    java.security.MessageDigest.getInstance("SHA-256").let { hash ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val size = it.read(buffer)
                            if (size < 0) break
                            hash.update(buffer, 0, size)
                        }
                        hash.digest().joinToString("") { byte -> "%02x".format(byte) } == entry.hash
                    }
                }
        if (!valid) {
            db.delete(
                "entries",
                "owner=? AND edition=? AND href=?",
                arrayOf(key.owner, key.edition, key.href),
            )
            if (file.exists()) check(file.delete()) { "Cannot discard a damaged cache entry" }
            return@withLock null
        }
        NovelOnlineBody("", true, entry.bindings) // Validate bounded private metadata too.
        db.update(
            "entries",
            ContentValues().apply { put("touched", nextStamp()) },
            "owner=? AND edition=? AND href=?",
            arrayOf(key.owner, key.edition, key.href),
        )
        entry
    }

    private suspend fun store(
        key: Key,
        bytes: ByteArray,
        type: String,
        bindings: Map<String, String>,
    ): Entry = writes.withLock {
        val encoded = NovelJson.encodeToString(bindings)
        val cost =
            bytes.size.toLong() +
                encoded.toByteArray(Charsets.UTF_8).size +
                key.href.length *
                2 +
                512
        require(cost <= byteLimit) { "Resource exceeds the online cache budget" }
        val db = database.writableDatabase
        makeRoom(db, cost)
        val hash = NovelOnlineIdentity.digest(bytes)
        val path =
            "objects/${NovelOnlineIdentity.digest("${key.owner}:${key.edition}:${key.href}")}-$hash.bin"
        val destination = owned(path)
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        val pending = File(destination.parentFile, "pending-${UUID.randomUUID()}")
        var committed = false
        try {
            pending.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(pending.renameTo(destination)) { "Cannot publish the verified chapter cache" }
            db.insertWithOnConflict(
                "entries",
                null,
                ContentValues().apply {
                    put("owner", key.owner)
                    put("edition", key.edition)
                    put("href", key.href)
                    put("hash", hash)
                    put("path", path)
                    put("mime", type)
                    put("bytes", bytes.size)
                    put("cost", cost)
                    put("bindings", encoded)
                    put("touched", nextStamp())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
                .also { check(it != -1L) }
            committed = true
            Entry(key, hash, path, type, bytes.size.toLong(), bindings)
        } finally {
            pending.delete()
            if (!committed) destination.delete()
        }
    }

    private fun makeRoom(db: SQLiteDatabase, incoming: Long) {
        var cost = 0L
        var count = 0
        db.rawQuery("SELECT COALESCE(SUM(cost),0),COUNT(*) FROM entries", null).use {
            it.moveToFirst()
            cost = it.getLong(0)
            count = it.getInt(1)
        }
        if (cost + incoming <= byteLimit && count < entryLimit) return
        val candidates =
            db.query(
                "entries",
                arrayOf("owner", "edition", "href", "path", "cost"),
                null,
                null,
                null,
                null,
                "touched ASC,rowid ASC",
            )
                .use { rows ->
                    buildList {
                        while (rows.moveToNext()) {
                            add(
                                Triple(
                                    Key(rows.getString(0), rows.getString(1), rows.getString(2)),
                                    rows.getString(3),
                                    rows.getLong(4),
                                ),
                            )
                        }
                    }
                }
        for ((key, path, entryCost) in candidates) {
            if (cost + incoming <= byteLimit && count < entryLimit) break
            if ((pins[key]?.get() ?: 0) > 0) continue
            val file = owned(path)
            if (file.exists() && !file.delete()) continue
            db.delete(
                "entries",
                "owner=? AND edition=? AND href=?",
                arrayOf(key.owner, key.edition, key.href),
            )
            cost -= entryCost
            count--
        }
        if (cost + incoming > byteLimit || count >= entryLimit) {
            throw IOException("Online cache is busy or full")
        }
    }

    private fun nextStamp(): Long = stamp.updateAndGet { nextNovelTimestamp(it) }

    private fun initialize() {
        if (initialized) return
        val db = database.writableDatabase
        val paths =
            db.query("entries", arrayOf("path"), null, null, null, null, null).use { rows ->
                buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
            }
        // Only this cache's known file names are recovered; originals and downloaded books live
        // elsewhere. An interrupted rename/SQL commit cannot accumulate unindexed objects.
        File(root, "objects").listFiles()?.forEach { file ->
            if (
                file.isFile &&
                (
                    file.name.matches(Regex("[a-f0-9]{64}-[a-f0-9]{64}\\.bin")) ||
                        file.name.matches(Regex("pending-[a-f0-9-]{36}"))
                    ) &&
                "objects/${file.name}" !in paths
            ) {
                file.delete()
            }
        }
        db.rawQuery("SELECT COALESCE(MAX(touched),0) FROM entries", null).use { rows ->
            rows.moveToFirst()
            stamp.updateAndGet { maxOf(it, rows.getLong(0)) }
        }
        initialized = true
    }

    private fun owned(path: String): File {
        require(path.matches(Regex("objects/[a-f0-9]{64}-[a-f0-9]{64}\\.bin")))
        val base = root.canonicalFile
        return File(base, path).canonicalFile.also {
            require(it.toPath().startsWith(base.toPath()))
        }
    }

    private fun verifyImage(image: NovelOnlineImage) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
        require(
            options.outWidth in 1..8192 &&
                options.outHeight in 1..8192 &&
                options.outWidth.toLong() * options.outHeight <= 16L * 1024 * 1024 &&
                options.outMimeType == image.mediaType,
        ) {
            "Unsupported or oversized chapter illustration"
        }
    }

    private class Database(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 1) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onOpen(db: SQLiteDatabase) {
            db.rawQuery("PRAGMA max_page_count=32768", null).use { it.moveToFirst() }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE entries(owner TEXT NOT NULL,edition TEXT NOT NULL,href TEXT NOT NULL,hash TEXT NOT NULL,path TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,cost INTEGER NOT NULL,bindings TEXT NOT NULL,touched INTEGER NOT NULL,PRIMARY KEY(owner,edition,href))",
            )
            db.execSQL("CREATE INDEX entries_age ON entries(touched)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported online cache migration: $oldVersion to $newVersion")
        }
    }
}
