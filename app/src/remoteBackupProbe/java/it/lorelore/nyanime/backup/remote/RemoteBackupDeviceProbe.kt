package it.lorelore.nyanime.backup.remote

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.UUID

/** Private fixtures and a temporary complete backup; never uploads or restores user data. */
class RemoteBackupDeviceProbe : Instrumentation() {
    private var cleanupRoom: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        cleanupRoom = arguments?.getString("cleanupSyncplayRoom")?.takeIf {
            it.matches(Regex("QA-[0-9a-f]{32}"))
        }
        start()
    }
    override fun onStart() {
        val report = Bundle()
        var result = Activity.RESULT_OK
        val root = File(targetContext.cacheDir, "remote-probe-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            // Recover only this probe's directories if an older instrumentation was terminated.
            targetContext.cacheDir.listFiles()?.filter {
                it != root && it.isDirectory && it.name.matches(Regex("remote-probe-[0-9a-f-]{36}"))
            }?.forEach(::removeProbeDirectory)
            runBlocking {
                val rootUrl = "https://storage.example/nyanime-backups-v1/".toHttpUrl()
                val name = "nyanime-${UUID.randomUUID()}.json"
                val xml = "<d:multistatus xmlns:d='DAV:'><d:response><d:href>${rootUrl}$name</d:href>" +
                    "<d:propstat><d:prop><d:getcontentlength>42</d:getcontentlength></d:prop>" +
                    "</d:propstat></d:response></d:multistatus>"
                check(parseWebDavObjects(xml, rootUrl).single().name == name)
                val hostile = "<!DOCTYPE data [<!ENTITY x SYSTEM 'file:///private'>]>" +
                    "<d:multistatus xmlns:d='DAV:'>&x;</d:multistatus>"
                check(runCatching { parseWebDavObjects(hostile, rootUrl) }.isFailure)
                val store = object : RemoteFileStore {
                    val values = mutableMapOf<String, ByteArray>()
                    override suspend fun prepare() = Unit
                    override suspend fun list() = values.map { RemoteObject(it.key, it.key, it.value.size.toLong()) }
                    override suspend fun upload(name: String, file: File): RemoteObject {
                        values.putIfAbsent(name, file.readBytes())
                        return RemoteObject(name, name, file.length())
                    }
                    override suspend fun download(id: String, file: File, limit: Long) {
                        val bytes = requireNotNull(values[id])
                        check(bytes.size <= limit)
                        file.writeBytes(bytes)
                    }
                    override suspend fun delete(id: String) {
                        values.remove(id)
                    }
                }
                val key = RemoteRecoveryKey.decode(RemoteRecoveryKey.create())
                try {
                    val isolatedContext = object : android.content.ContextWrapper(targetContext) {
                        override fun getNoBackupFilesDir(): File = root
                    }
                    val vault = RemoteBackupVault(isolatedContext)
                    val configuration = RemoteBackupConfiguration(
                        RemoteBackupProvider.WebDAV,
                        "https://storage.example/folder/",
                        "test-user",
                        "test-password",
                        RemoteRecoveryKey.encode(key),
                    )
                    vault.set(configuration)
                    check(vault.get(RemoteBackupProvider.WebDAV) == configuration)
                    check(vault.device() == vault.device())
                    val protected = File(root, "remote-backup-v1/WebDAV").readBytes()
                    check(!protected.toString(Charsets.UTF_8).contains("test-password"))
                    vault.remove(RemoteBackupProvider.WebDAV)
                    check(vault.get(RemoteBackupProvider.WebDAV) == null)
                    val original = File(root, "original").apply {
                        writeBytes(ByteArray(1250000) { (it % 251).toByte() })
                    }
                    val engine = RemoteBackupEngine(store, root)
                    val entry = engine.upload(
                        original,
                        File(root, "cipher"),
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "Test device",
                        1234L,
                        key,
                    ) {}
                    check(engine.list(key).single() == entry)
                    val restored = File(root, "restored")
                    engine.restore(entry, restored, key) { check(it.length() == original.length()) }
                    check(original.readBytes().contentEquals(restored.readBytes()))
                    check(engine.list(ByteArray(32) { 9 }).isEmpty())
                    store.values[entry.commit.payload]!![100] =
                        (store.values[entry.commit.payload]!![100].toInt() xor 1).toByte()
                    check(runCatching { engine.restore(entry, restored, key) {} }.isFailure)
                    check(!restored.exists())
                } finally {
                    key.fill(0)
                }
                // Read-only snapshot of the real installation, kept in this private temporary
                // directory and deleted below. Never invoke the restore worker or change its data.
                val snapshot = File(root, "complete.nyabk")
                BackupCreator(targetContext, false).backup(Uri.fromFile(snapshot), BackupOptions.complete())
                check(snapshot.length() > 0)
                BackupFileValidator(targetContext).validate(Uri.fromFile(snapshot))
            }
            report.putString(
                "stream",
                "PASS: Android XML parser, rejected external entities, Keystore vault, streaming encryption, multi-segment restore, authenticated commit, wrong key, corrupted payload, complete backup creation and validation without restoring user data.\n",
            )
        } catch (failure: Throwable) {
            result = Activity.RESULT_CANCELED
            report.putString(
                "stream",
                failure.javaClass.simpleName + " at " + failure.stackTrace.take(8).joinToString(),
            )
        } finally {
            cleanupRoom?.let { room ->
                val preferences = targetContext.getSharedPreferences("syncplay_compatibility", 0)
                if (preferences.getString("server", "") == "127.0.0.1:18999" &&
                    preferences.getString("nickname", "") == "NyanimeQA" &&
                    preferences.getString("room", "") == room
                ) {
                    preferences.edit().remove("server").remove("nickname").remove("room").remove("tls").commit()
                }
            }
            runCatching { removeProbeDirectory(root) }.onFailure {
                result = Activity.RESULT_CANCELED
                report.putString("stream", "Probe temporary-file cleanup failed: ${it.javaClass.simpleName}\n")
            }
        }
        // finish() may terminate the instrumented process; remove private snapshots first.
        finish(result, report)
    }

    private fun removeProbeDirectory(root: File) {
        check(root.canonicalFile.parentFile == targetContext.cacheDir.canonicalFile)
        check(root.name == "remote-probe-${UUID.fromString(root.name.removePrefix("remote-probe-"))}")
        val vault = File(root, "remote-backup-v1")
        vault.listFiles()?.forEach { check(it.isFile && it.delete()) }
        if (vault.exists()) check(vault.delete())
        root.listFiles()?.forEach { check(it.isFile && it.delete()) }
        check(root.delete())
    }
}
