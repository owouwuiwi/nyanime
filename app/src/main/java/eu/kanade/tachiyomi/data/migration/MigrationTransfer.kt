package eu.kanade.tachiyomi.data.migration

import android.content.Context
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal data class MigrationProgress(
    val step: Int = 0,
    val bytes: Long = 0,
    val total: Long = 0,
    val done: Boolean = false,
    val failed: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
)

/** Persistent encrypted chunks make a disconnected provider or killed UI resumable. */
internal object MigrationTransfer {
    val progress = MutableStateFlow(MigrationProgress())
    private val running = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val input = java.util.concurrent.atomic.AtomicReference<InputStream?>()
    fun cancel() {
        cancelled.set(true)
        runCatching { input.getAndSet(null)?.close() }
    }
    private fun checkCancelled() {
        if (cancelled.get()) throw kotlinx.coroutines.CancellationException("Cancelled")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val busy get() = running.get()
    private fun authority() = "${eu.kanade.tachiyomi.BuildConfig.LEGACY_APPLICATION_ID}.migration"
    fun describe(
        context: Context,
    ): Bundle = checkNotNull(
        context.contentResolver.call(Uri.parse("content://${authority()}"), "describe", null, null),
    )
    fun call(context: Context, method: String, grant: String) =
        checkNotNull(context.contentResolver.call(Uri.parse("content://${authority()}"), method, grant, null))

    fun acquireStorage(context: Context, token: String, storage: String?): Boolean {
        if (storage == null || MigrationStoragePaths.owned(context, storage) != null) return true
        val uri = Uri.parse(storage)
        if (uri.scheme == "content") {
            if (MigrationSharedStorage.retain(context, storage)) return true
            runCatching { call(context, "storageGrant", token) }
            return MigrationSharedStorage.retain(context, storage)
        }
        return runCatching {
            MigrationSharedStorage.verify(context, storage, uri)
            true
        }.getOrDefault(false)
    }

    fun start(context: Context, token: String, storage: String?) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        cancelled.set(false)
        progress.value = MigrationProgress(working = true)
        scope.launch {
            var phase = "prepare"
            val heartbeat = scope.launch {
                while (running.get()) {
                    delay(30_000)
                    runCatching { call(app, "status", token) }
                }
            }
            try {
                checkCancelled()
                phase = "storage"
                check(acquireStorage(app, token, storage)) { "Shared folder permission required" }
                phase = "prepare"
                MigrationKeepAliveService.start(app)
                MigrationState.update(app) { it.put("token", token).put("storage", storage) }
                var status = call(app, "prepare", token)
                while (status.getString("phase") == "preparing") {
                    checkCancelled()
                    progress.value = MigrationProgress(bytes = status.getLong("bytes"), working = true)
                    delay(1000)
                    status = call(app, "status", token)
                }
                checkCancelled()
                if (status.getString("phase") !=
                    "ready"
                ) {
                    throw MigrationFailure.Remote(status.getString("error") ?: "prepare:Unavailable")
                }
                phase = "receive"
                val expected = requireNotNull(status.getString("sha256"))
                val total = status.getLong("archiveBytes")
                require(total > 0 && MigrationRestore.enoughSpace(app, total))
                val directory = File(MigrationState.directory(app), "receive").apply { mkdirs() }
                val metadata = File(directory, "transfer.json")
                val previous = runCatching { JSONObject(metadata.readText()) }.getOrNull()
                if (previous?.optString("sha256") != expected) {
                    check(directory.deleteRecursively())
                    check(directory.mkdirs())
                    metadata.writeText(JSONObject().put("sha256", expected).toString())
                }
                File(directory, "partial").delete()
                val parts = directory.listFiles().orEmpty().filter {
                    it.name.matches(Regex("part-[0-9]{8}"))
                }.sortedBy { it.name }
                var received = 0L
                var validParts = 0
                for ((index, file) in parts.withIndex()) {
                    checkCancelled()
                    val checked = runCatching {
                        require(file.name == "part-%08d".format(index))
                        MigrationCrypto.decrypt(file.inputStream()).use { input ->
                            val count = input.readNBytesCompat(ByteArray(CHUNK))
                            require(count in 1..CHUNK && received + count <= total && input.read() == -1)
                            count
                        }
                    }.getOrNull()
                    if (checked == null) {
                        parts.drop(index).forEach { check(it.delete()) }
                        break
                    }
                    received += checked
                    validParts++
                }
                progress.value = MigrationProgress(1, received, total, working = true)
                val uri = Uri.parse("content://${authority()}/snapshot/$token?offset=$received")
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    MigrationTransfer.input.set(input)
                    var index = validParts
                    val buffer = ByteArray(CHUNK)
                    while (received < total) {
                        checkCancelled()
                        val count = input.readNBytesCompat(buffer)
                        require(count > 0 && received + count <= total)
                        val partial = File(directory, "partial")
                        MigrationCrypto.encrypt(partial.outputStream()).use { it.write(buffer, 0, count) }
                        check(partial.renameTo(File(directory, "part-%08d".format(index++))))
                        received += count
                        progress.value = MigrationProgress(1, received, total, working = true)
                    }
                    require(input.read() == -1)
                }
                val completeParts = directory.listFiles().orEmpty().filter {
                    it.name.startsWith("part-")
                }.sortedBy { it.name }
                phase = "integrity"
                progress.value = MigrationProgress(2, total, total, working = true)
                val digest = MessageDigest.getInstance("SHA-256")
                join(completeParts).use { input ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == expected)
                val stage = File(MigrationState.directory(app), "stage")
                check(stage.deleteRecursively())
                val manifest = join(completeParts).use { MigrationArchive.extract(it, stage, status.getLong("bytes")) }
                checkCancelled()
                phase = "restore"
                MigrationRestore.commit(app, stage, manifest, storage)
                runCatching { call(app, "finish", token) }
                // A cleanup failure must not turn a verified, committed migration into a failure.
                runCatching { MigrationFiles.deleteTree(stage) }
                runCatching { MigrationFiles.deleteTree(directory) }
                MigrationState.update(app) {
                    it.remove("token")
                    it.remove("lastError")
                    it.remove("lastErrorFrames")
                }
                progress.value = MigrationProgress(2, total, total, done = true)
            } catch (error: Exception) {
                runCatching { File(MigrationState.directory(app), "stage").deleteRecursively() }
                if (cancelled.get()) {
                    progress.value = MigrationProgress()
                } else {
                    MigrationFailure.record(app, phase, error)
                    progress.value =
                        progress.value.copy(failed = true, working = false, error = MigrationFailure.code(phase, error))
                }
            } finally {
                input.getAndSet(null)?.let { runCatching { it.close() } }
                if (!progress.value.done) runCatching { call(app, "abort", token) }
                heartbeat.cancel()
                running.set(false)
                if (!MigrationState.state(app).has("transaction")) MigrationState.unfreeze(app)
                MigrationKeepAliveService.stop(app)
            }
        }
    }

    private const val CHUNK = 1024 * 1024
    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = read(buffer, count, buffer.size - count)
            if (read < 0) break
            require(read > 0)
            count += read
        }
        return count
    }

    private fun join(parts: List<File>): InputStream = object : InputStream() {
        var index = 0
        var current: InputStream? = null
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (true) {
                if (current == null) {
                    if (index == parts.size) return -1
                    current = MigrationCrypto.decrypt(parts[index++].inputStream())
                }
                val count = current!!.read(buffer, offset, length)
                if (count >= 0) return count
                current!!.close()
                current = null
            }
        }
        override fun close() {
            current?.close()
            current = null
            index = parts.size
        }
    }
}
