package it.lorelore.nyanime.novel

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Explicit durable downloads, never an LRU cache and never a portable backup attachment. */
class NovelDownloadStore(
    context: Context,
    private val root: File = File(context.filesDir, "novel-downloads-v1"),
    databaseName: String = "novel_downloads_v1.db",
    private val byteLimit: Long = NovelDownloadPolicy.MAX_BOOK_BYTES,
    private val resourceLimit: Int = NovelDownloadPolicy.MAX_RESOURCES,
    private val diskReserve: Long = NovelDownloadPolicy.DISK_RESERVE,
) : AutoCloseable {
    init {
        require(byteLimit in 1..NovelDownloadPolicy.MAX_BOOK_BYTES)
        require(resourceLimit in 1..NovelDownloadPolicy.MAX_RESOURCES && diskReserve >= 0)
    }

    private val database = Database(context.applicationContext, databaseName)
    private val writes = Mutex()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private val pins = ConcurrentHashMap<String, AtomicInteger>()

    private data class DiskUsage(var bytes: Long, var files: Int)

    private val usage = linkedMapOf<String, DiskUsage>()

    data class Lease(val plan: NovelDownloadPlan, internal val token: String)

    private data class Resource(
        val href: String,
        val hash: String,
        val name: String,
        val mime: String,
        val bytes: Long,
    )

    fun observe(bookId: String) = revision.map { progress(bookId) }.distinctUntilChanged()

    fun observeList(limit: Int = 40) = revision.map { list(limit) }.distinctUntilChanged()

    suspend fun list(limit: Int = 40): List<NovelDownloadSummary> {
        require(limit in 1..200)
        val titles = io {
            database.readableDatabase
                .rawQuery(
                    "SELECT owner,title FROM jobs ORDER BY CASE state WHEN 'RUNNING' THEN 0 WHEN 'QUEUED' THEN 1 WHEN 'FAILED' THEN 2 WHEN 'PAUSED' THEN 3 ELSE 4 END, title COLLATE NOCASE,owner LIMIT ?",
                    arrayOf(limit.toString()),
                )
                .use { rows ->
                    buildList {
                        while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1))
                    }
                }
        }
        return titles.mapNotNull { (id, title) ->
            progress(id)?.let { NovelDownloadSummary(title, it) }
        }
    }

    suspend fun document(book: NovelBook): NovelOnlineDocument? = io {
        plan(database.readableDatabase, book.id)
            ?.takeIf { it.book.contentVersion == book.contentVersion }
            ?.document
    }

    suspend fun enqueue(plan: NovelDownloadPlan): NovelDownloadPlan = io {
        val db = database.writableDatabase
        val completed =
            db.query(
                "chapters",
                arrayOf("href"),
                "owner=? AND edition=?",
                arrayOf(plan.book.id, plan.book.contentVersion),
                null,
                null,
                null,
            )
                .use { rows ->
                    buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
                }
        // Keep downloaded chapters, but a new choice replaces unfinished selections.
        val requested = completed + plan.selected
        val merged =
            plan.copy(selected = plan.document.chapters.map { it.href }.filter { it in requested })
        val json = NovelJson.encodeToString(merged)
        require(json.toByteArray().size <= 16 * 1024 * 1024)
        transaction(db) {
            db.insertWithOnConflict(
                "jobs",
                null,
                ContentValues().apply {
                    put("owner", merged.book.id)
                    put("job", merged.id)
                    put("title", merged.book.title)
                    put("edition", merged.book.contentVersion)
                    put("plan", json)
                    put("state", NovelDownloadState.QUEUED.name)
                    putNull("lease")
                    putNull("active")
                    put("done_resources", 0)
                    put("total_resources", 0)
                    putNull("problem")
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
                .also { check(it != -1L) }
            db.delete("selection", "owner=?", arrayOf(merged.book.id))
            merged.selected.forEach { href ->
                db.insertOrThrow(
                    "selection",
                    null,
                    ContentValues().apply {
                        put("owner", merged.book.id)
                        put("href", href)
                    },
                )
            }
        }
        changed()
        merged
    }

    suspend fun progress(bookId: String): NovelDownloadProgress? = io {
        val db = database.readableDatabase
        db.rawQuery(
            "SELECT job,state,active,done_resources,total_resources,problem,edition FROM jobs WHERE owner=?",
            arrayOf(bookId),
        )
            .use { row ->
                if (!row.moveToFirst()) return@io null
                val edition = row.getString(6)
                val counts =
                    db.rawQuery(
                        "SELECT COUNT(*),COUNT(c.href) FROM selection s LEFT JOIN chapters c ON c.owner=s.owner AND c.href=s.href AND c.edition=? WHERE s.owner=?",
                        arrayOf(edition, bookId),
                    )
                        .use {
                            it.moveToFirst()
                            it.getInt(0) to it.getInt(1)
                        }
                val bytes =
                    db.rawQuery(
                        "SELECT COALESCE(SUM(bytes),0) FROM resources WHERE owner=? AND edition=?",
                        arrayOf(bookId, edition),
                    )
                        .use {
                            it.moveToFirst()
                            it.getLong(0)
                        }
                NovelDownloadProgress(
                    row.getString(0),
                    bookId,
                    NovelDownloadState.valueOf(row.getString(1)),
                    counts.first,
                    counts.second,
                    bytes,
                    row.optional(2),
                    row.getInt(3),
                    row.getInt(4),
                    row.optional(5)?.let(NovelDownloadProblem::valueOf),
                )
            }
    }

    suspend fun claim(jobId: String): Lease? = io {
        val db = database.writableDatabase
        val value =
            db.query("jobs", arrayOf("plan", "state"), "job=?", arrayOf(jobId), null, null, null)
                .use {
                    if (!it.moveToFirst()) return@io null
                    if (
                        it.getString(1) == NovelDownloadState.READY.name ||
                        it.getString(1) == NovelDownloadState.PAUSED.name
                    ) {
                        return@io null
                    }
                    NovelJson.decodeFromString<NovelDownloadPlan>(it.getString(0))
                }
        val token = UUID.randomUUID().toString()
        db.update(
            "jobs",
            ContentValues().apply {
                put("lease", token)
                put("state", NovelDownloadState.RUNNING.name)
                putNull("problem")
            },
            "job=?",
            arrayOf(jobId),
        )
        changed()
        Lease(value, token)
    }

    suspend fun retry(bookId: String): String? = io {
        val db = database.writableDatabase
        val id = plan(db, bookId)?.id ?: return@io null
        db.update(
            "jobs",
            ContentValues().apply {
                put("state", NovelDownloadState.QUEUED.name)
                putNull("lease")
                putNull("problem")
                putNull("active")
                put("done_resources", 0)
                put("total_resources", 0)
            },
            "owner=?",
            arrayOf(bookId),
        )
        changed()
        id
    }

    suspend fun pause(bookId: String) = io {
        database.writableDatabase.update(
            "jobs",
            ContentValues().apply {
                put("state", NovelDownloadState.PAUSED.name)
                putNull("lease")
                putNull("active")
                put("done_resources", 0)
                put("total_resources", 0)
            },
            "owner=? AND state!=?",
            arrayOf(bookId, NovelDownloadState.READY.name),
        )
        changed()
    }

    /** WorkManager may stop for constraints or process lifecycle; leave an attempt resumable. */
    suspend fun release(lease: Lease, paused: Boolean = false) = io {
        database.writableDatabase.update(
            "jobs",
            ContentValues().apply {
                put(
                    "state",
                    if (paused) NovelDownloadState.PAUSED.name else NovelDownloadState.QUEUED.name,
                )
                putNull("lease")
                putNull("active")
                put("done_resources", 0)
                put("total_resources", 0)
            },
            "job=? AND lease=?",
            arrayOf(lease.plan.id, lease.token),
        )
        changed()
    }

    suspend fun active(lease: Lease, href: String, done: Int, total: Int) = io {
        require(href in lease.plan.selected && done in 0..total && total in 1..201)
        checkLease(lease)
        val previous =
            database.readableDatabase
                .rawQuery(
                    "SELECT active,done_resources FROM jobs WHERE job=?",
                    arrayOf(lease.plan.id),
                )
                .use {
                    if (it.moveToFirst() && it.optional(0) == href) it.getInt(1) else 0
                }
        database.writableDatabase.update(
            "jobs",
            ContentValues().apply {
                put("active", href)
                put("done_resources", maxOf(previous, done))
                put("total_resources", total)
            },
            "job=? AND lease=?",
            arrayOf(lease.plan.id, lease.token),
        )
        changed()
    }

    suspend fun finish(lease: Lease, problem: NovelDownloadProblem? = null) = io {
        checkLease(lease)
        if (problem == null) {
            val value = progressLocked(lease.plan.book.id)
            check(value == lease.plan.selected.size) { "Downloaded chapters are incomplete" }
        }
        database.writableDatabase.update(
            "jobs",
            ContentValues().apply {
                put(
                    "state",
                    if (problem == null) {
                        NovelDownloadState.READY.name
                    } else {
                        NovelDownloadState.FAILED.name
                    },
                )
                put("problem", problem?.name)
                putNull("lease")
                putNull("active")
                put("done_resources", 0)
                put("total_resources", 0)
            },
            "job=? AND lease=?",
            arrayOf(lease.plan.id, lease.token),
        )
        changed()
    }

    /**
     * File publication precedes the SQL row. A late or cancelled worker cannot publish metadata.
     */
    suspend fun save(lease: Lease, href: String, value: NovelOnlineFile): Unit = io {
        checkLease(lease)
        require(NovelDownloadPolicy.validHref(href))
        require(
            if (NovelDownloadPolicy.chapterHref(href)) {
                value.mediaType.toString() in setOf("text/html", "application/xhtml+xml")
            } else {
                value.mediaType.toString() in
                    setOf("image/png", "image/jpeg", "image/webp", "image/gif")
            },
        )
        val bytes = value.file.length()
        if (bytes !in 1..NovelDownloadPolicy.MAX_RESOURCE_BYTES) {
            throw NovelDownloadException(NovelDownloadProblem.LIMIT)
        }
        val db = database.writableDatabase
        val old = resource(db, lease.plan.book, href)
        if (old?.hash == value.sha256 && verified(lease.plan.book.id, old)) return@io
        val name = NovelDownloadPolicy.objectName(href, value.sha256)
        val target = NovelDownloadPolicy.owned(root, lease.plan.book.id, name)
        val oldFile = old?.let { NovelDownloadPolicy.owned(root, lease.plan.book.id, it.name) }
        val replacing = if (target.isFile) target.length() else 0L
        val reclaim =
            if (
                oldFile != null &&
                oldFile != target &&
                (pins[lease.plan.book.id]?.get() ?: 0) == 0 &&
                oldFile.isFile
            ) {
                oldFile.length()
            } else {
                0L
            }
        val disk = diskUsage(lease.plan.book.id)
        val futureCount = disk.files + (if (target.isFile) 0 else 1) - (if (reclaim > 0) 1 else 0)
        if (disk.bytes - replacing - reclaim + bytes > byteLimit || futureCount > resourceLimit) {
            throw NovelDownloadException(NovelDownloadProblem.LIMIT)
        }
        check(root.isDirectory || root.mkdirs())
        val usable = root.usableSpace
        if (usable < diskReserve || usable - diskReserve < bytes) {
            throw NovelDownloadException(NovelDownloadProblem.SPACE)
        }
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val pending =
            NovelDownloadPolicy.owned(root, lease.plan.book.id, "pending-${UUID.randomUUID()}")
        try {
            value.file.inputStream().use { input ->
                pending.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        copied += size
                        if (copied > bytes || copied > NovelDownloadPolicy.MAX_RESOURCE_BYTES) {
                            throw IOException("Resource changed during download")
                        }
                        output.write(buffer, 0, size)
                    }
                    check(copied == bytes)
                    output.fd.sync()
                }
            }
            val context = coroutineContext
            if (NovelDownloadPolicy.digest(pending) { context.ensureActive() } != value.sha256) {
                throw NovelDownloadException(NovelDownloadProblem.DAMAGED)
            }
            coroutineContext.ensureActive()
            checkLease(lease)
            Files.move(
                pending.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            disk.bytes += bytes - replacing
            if (replacing == 0L) disk.files++
            db.insertWithOnConflict(
                "resources",
                null,
                ContentValues().apply {
                    put("owner", lease.plan.book.id)
                    put("edition", lease.plan.book.contentVersion)
                    put("href", href)
                    put("hash", value.sha256)
                    put("name", name)
                    put("mime", value.mediaType.toString())
                    put("bytes", bytes)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
                .also { check(it != -1L) }
            if (reclaim > 0 && oldFile?.delete() == true) {
                disk.bytes -= reclaim
                disk.files--
            }
            // Superseded files stay while any reader for this book is open, then explicit GC
            // reclaims them.
            changed()
        } catch (failure: NovelDownloadException) {
            throw failure
        } catch (_: IOException) {
            throw NovelDownloadException(NovelDownloadProblem.STORAGE)
        } finally {
            pending.delete()
        }
    }

    suspend fun complete(lease: Lease, href: String, images: List<String>) = io {
        checkLease(lease)
        require(
            href in lease.plan.selected &&
                images.size <= 200 &&
                images.distinct().size == images.size &&
                images.all {
                    NovelDownloadPolicy.validHref(it) && !NovelDownloadPolicy.chapterHref(it)
                },
        )
        val db = database.writableDatabase
        val body =
            resource(db, lease.plan.book, href) ?: throw IOException("Missing downloaded chapter")
        check(verified(lease.plan.book.id, body))
        check(
            NovelDownloadPolicy.images(
                NovelDownloadPolicy.owned(root, lease.plan.book.id, body.name).readBytes(),
            ) == images,
        )
        images.forEach { image ->
            val resource =
                resource(db, lease.plan.book, image)
                    ?: throw IOException("Missing downloaded illustration")
            check(verified(lease.plan.book.id, resource))
        }
        db.insertWithOnConflict(
            "chapters",
            null,
            ContentValues().apply {
                put("owner", lease.plan.book.id)
                put("edition", lease.plan.book.contentVersion)
                put("href", href)
                put("hash", body.hash)
                put("images", NovelJson.encodeToString(images))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
            .also { check(it != -1L) }
        changed()
    }

    suspend fun complete(lease: Lease, href: String): Boolean = io {
        checkLease(lease)
        val db = database.writableDatabase
        val complete =
            db.query(
                "chapters",
                arrayOf("hash", "images"),
                "owner=? AND edition=? AND href=?",
                arrayOf(lease.plan.book.id, lease.plan.book.contentVersion, href),
                null,
                null,
                null,
            )
                .use {
                    if (!it.moveToFirst()) return@io false
                    it.getString(0) to NovelJson.decodeFromString<List<String>>(it.getString(1))
                }
        val resources = listOf(href) + complete.second
        val valid = resources.all { entry ->
            resource(db, lease.plan.book, entry)?.let { value ->
                (entry != href || value.hash == complete.first) &&
                    verified(lease.plan.book.id, value)
            } == true
        }
        if (!valid) {
            db.delete(
                "chapters",
                "owner=? AND edition=? AND href=?",
                arrayOf(lease.plan.book.id, lease.plan.book.contentVersion, href),
            )
            changed()
        }
        valid
    }

    suspend fun containsVerified(lease: Lease, href: String): Boolean = io {
        checkLease(lease)
        resource(database.readableDatabase, lease.plan.book, href)?.let {
            verified(lease.plan.book.id, it)
        } == true
    }

    /** Copies are leased by the reading session; removing downloads cannot unlink a live file. */
    suspend fun open(book: NovelBook, document: NovelOnlineDocument): NovelOnlineAccess = io {
        require(book.id == document.bookId && book.origin == document.origin)
        val db = database.readableDatabase
        val available =
            db.query(
                "chapters",
                arrayOf("href", "images"),
                "owner=? AND edition=?",
                arrayOf(book.id, book.contentVersion),
                null,
                null,
                null,
            )
                .use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) {
                            add(cursor.getString(0))
                            addAll(NovelJson.decodeFromString<List<String>>(cursor.getString(1)))
                        }
                    }
                }
        val rows =
            db.query(
                "resources",
                arrayOf("href", "hash", "name", "mime", "bytes"),
                "owner=? AND edition=?",
                arrayOf(book.id, book.contentVersion),
                null,
                null,
                null,
            )
                .use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) cursor.resource().let { put(it.href, it) }
                    }
                }
                .filterKeys { it in available }
        val lease = pin(book.id)
        Access(book, document, rows, lease)
    }

    suspend fun remove(bookId: String) = io {
        // Changing the lease first prevents a superseded worker from writing after removal.
        database.writableDatabase.update(
            "jobs",
            ContentValues().apply {
                put("state", NovelDownloadState.PAUSED.name)
                putNull("lease")
            },
            "owner=?",
            arrayOf(bookId),
        )
        if ((pins[bookId]?.get() ?: 0) > 0) {
            changed()
            throw IOException("Close the reader before removing its downloads")
        }
        val db = database.writableDatabase
        transaction(db) {
            listOf("selection", "chapters", "resources", "jobs").forEach {
                db.delete(it, "owner=?", arrayOf(bookId))
            }
        }
        collectFiles(bookId, emptySet())
        changed()
    }

    suspend fun collect(bookId: String) = io {
        if ((pins[bookId]?.get() ?: 0) > 0) return@io
        val names =
            database.readableDatabase
                .query("resources", arrayOf("name"), "owner=?", arrayOf(bookId), null, null, null)
                .use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
        collectFiles(bookId, names)
    }

    private inner class Access(
        private val book: NovelBook,
        override val document: NovelOnlineDocument,
        private val rows: Map<String, Resource>,
        private val lease: AutoCloseable,
    ) : NovelOnlineAccess {
        private val closed = AtomicBoolean(false)
        private val versions =
            MutableStateFlow(
                rows.filterKeys(NovelDownloadPolicy::chapterHref).mapValues { it.value.hash },
            )
        override val chapterVersions = versions

        override fun contains(href: String) = !closed.get() && href in rows

        override fun retain(href: String): AutoCloseable {
            check(contains(href))
            return pin(book.id)
        }

        override suspend fun cachedOnly(): NovelOnlineAccess = open(book, document)

        override suspend fun materialize(href: String): NovelOnlineFile = io {
            check(!closed.get())
            val resource = rows[href] ?: throw IOException("Chapter is not downloaded")
            if (!verified(book.id, resource)) {
                versions.value = versions.value - href
                throw NovelDownloadException(NovelDownloadProblem.DAMAGED)
            }
            NovelOnlineFile(
                NovelDownloadPolicy.owned(root, book.id, resource.name),
                requireNotNull(MediaType(resource.mime)),
                resource.hash,
            )
        }

        override fun close() {
            if (!closed.getAndSet(true)) lease.close()
        }
    }

    private fun collectFiles(bookId: String, names: Set<String>) {
        usage.remove(bookId)
        // Validate even an empty directory's owner; never recursively delete computed paths.
        val sample =
            NovelDownloadPolicy.owned(root, bookId, "pending-00000000-0000-0000-0000-000000000000")
        sample.parentFile?.listFiles()?.forEach {
            if (
                it.isFile &&
                it.name !in names &&
                it.name.matches(Regex("[a-f0-9]{64}-[a-f0-9]{64}\\.bin|pending-[a-f0-9-]{36}"))
            ) {
                val owned = NovelDownloadPolicy.owned(root, bookId, it.name)
                if (owned.exists() && !owned.delete()) {
                    throw IOException("Cannot remove a downloaded resource")
                }
            }
        }
    }

    private fun diskUsage(bookId: String): DiskUsage {
        usage.remove(bookId)?.let {
            usage[bookId] = it
            return it
        }
        val sample =
            NovelDownloadPolicy.owned(root, bookId, "pending-00000000-0000-0000-0000-000000000000")
        val files =
            sample.parentFile?.listFiles().orEmpty().filter {
                it.isFile && it.name.matches(Regex("[a-f0-9]{64}-[a-f0-9]{64}\\.bin"))
            }
        val value = DiskUsage(files.sumOf { it.length() }, files.size)
        usage[bookId] = value
        while (usage.size > 32) usage.remove(usage.keys.first())
        return value
    }

    private fun pin(bookId: String): AutoCloseable {
        pins.compute(bookId) { _, value -> (value ?: AtomicInteger()).apply { incrementAndGet() } }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (!closed.getAndSet(true)) {
                pins.computeIfPresent(bookId) { _, value ->
                    if (value.decrementAndGet() == 0) null else value
                }
            }
        }
    }

    private fun plan(db: SQLiteDatabase, bookId: String): NovelDownloadPlan? =
        db.query("jobs", arrayOf("plan"), "owner=?", arrayOf(bookId), null, null, null).use {
            if (it.moveToFirst()) NovelJson.decodeFromString(it.getString(0)) else null
        }

    private fun resource(db: SQLiteDatabase, book: NovelBook, href: String): Resource? =
        db.query(
            "resources",
            arrayOf("href", "hash", "name", "mime", "bytes"),
            "owner=? AND edition=? AND href=?",
            arrayOf(book.id, book.contentVersion, href),
            null,
            null,
            null,
        )
            .use {
                if (it.moveToFirst()) it.resource() else null
            }

    private fun Cursor.resource() =
        Resource(getString(0), getString(1), getString(2), getString(3), getLong(4))

    private fun Cursor.optional(column: Int): String? =
        if (isNull(column)) null else getString(column)

    private suspend fun verified(bookId: String, resource: Resource): Boolean {
        val file = NovelDownloadPolicy.owned(root, bookId, resource.name)
        if (!file.isFile || file.length() != resource.bytes) return false
        val context = coroutineContext
        return NovelDownloadPolicy.digest(file) { context.ensureActive() } == resource.hash
    }

    private fun progressLocked(bookId: String): Int =
        database.readableDatabase
            .rawQuery(
                "SELECT COUNT(*) FROM selection s JOIN jobs j ON j.owner=s.owner JOIN chapters c ON c.owner=s.owner AND c.href=s.href AND c.edition=j.edition WHERE s.owner=?",
                arrayOf(bookId),
            )
            .use {
                it.moveToFirst()
                it.getInt(0)
            }

    private fun checkLease(lease: Lease) {
        val valid =
            database.readableDatabase
                .rawQuery(
                    "SELECT 1 FROM jobs WHERE job=? AND lease=? AND state=?",
                    arrayOf(lease.plan.id, lease.token, NovelDownloadState.RUNNING.name),
                )
                .use { it.moveToFirst() }
        check(valid) { "Download lease has been superseded" }
    }

    private fun changed() {
        revision.value += 1
    }

    private suspend fun <T> io(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { writes.withLock { block() } }

    private inline fun transaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = database.close()

    private class Database(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE jobs(owner TEXT PRIMARY KEY,job TEXT UNIQUE NOT NULL,title TEXT NOT NULL,edition TEXT NOT NULL,plan TEXT NOT NULL,state TEXT NOT NULL,lease TEXT,active TEXT,done_resources INTEGER NOT NULL,total_resources INTEGER NOT NULL,problem TEXT)",
            )
            db.execSQL(
                "CREATE TABLE selection(owner TEXT NOT NULL,href TEXT NOT NULL,PRIMARY KEY(owner,href))",
            )
            db.execSQL(
                "CREATE TABLE resources(owner TEXT NOT NULL,edition TEXT NOT NULL,href TEXT NOT NULL,hash TEXT NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,PRIMARY KEY(owner,edition,href))",
            )
            db.execSQL(
                "CREATE TABLE chapters(owner TEXT NOT NULL,edition TEXT NOT NULL,href TEXT NOT NULL,hash TEXT NOT NULL,images TEXT NOT NULL,PRIMARY KEY(owner,edition,href))",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) =
            error("Unsupported download schema")
    }
}
