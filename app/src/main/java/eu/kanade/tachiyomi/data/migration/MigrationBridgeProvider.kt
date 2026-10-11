package eu.kanade.tachiyomi.data.migration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.BuildConfig
import java.util.UUID
import java.util.concurrent.Executors

/** Migration capability is independent of addon trust and cannot select arbitrary paths. */
class MigrationBridgeProvider : ContentProvider() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean()
    private val preparing = java.util.concurrent.atomic.AtomicBoolean()

    @Volatile private var result = Bundle()

    @Volatile private var prepared: PreparedMigration? = null
    override fun onCreate(): Boolean {
        val context = requireNotNull(context)
        val directory = java.io.File(MigrationState.directory(context), "export")
        runCatching {
            val saved = org.json.JSONObject(java.io.File(directory, "ready.json").readText())
            val file = java.io.File(directory, "snapshot.encrypted")
            check(file.isFile && MigrationState.state(context).optLong("grantUntil") > System.currentTimeMillis())
            prepared = PreparedMigration(
                file,
                saved.getString("sha256"),
                kotlinx.serialization.json.Json.decodeFromString(
                    MigrationManifest.serializer(),
                    saved.getString("manifest"),
                ),
            )
            result = ready(checkNotNull(prepared), saved.getLong("plainBytes"))
        }
        return true
    }

    private fun ready(snapshot: PreparedMigration, plainBytes: Long) = Bundle().apply {
        putString("phase", "ready")
        putLong("bytes", snapshot.manifest.bytes)
        putLong("archiveBytes", plainBytes)
        putInt("files", snapshot.manifest.files.size)
        putString("sha256", snapshot.digest)
    }

    private fun caller(): String {
        val context = requireNotNull(context)
        check(context.packageName == BuildConfig.LEGACY_APPLICATION_ID)
        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        require(packages.size == 1 && packages.single() == BuildConfig.PRODUCTION_APPLICATION_ID)
        check(MigrationState.verified(context, packages.single(), BuildConfig.PRODUCTION_CERT_SHA256))
        return packages.single()
    }

    private fun authorized(token: String?) {
        caller()
        val context = requireNotNull(context)
        val state = MigrationState.state(context)
        require(
            token != null &&
                token == state.optString("grant") &&
                state.optLong("grantUntil") > System.currentTimeMillis(),
        )
        MigrationState.update(context) { it.put("grantUntil", System.currentTimeMillis() + 10 * 60_000) }
    }

    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        caller()
        val context = requireNotNull(context)
        if (method == "describe") {
            return Bundle().apply {
                putInt("protocol", 2)
                putInt("minimumReceiver", 219)
                putInt("versionCode", BuildConfig.VERSION_CODE)
                putString("storage", MigrationSnapshot.storage(context))
                putString(
                    "privateStoragePath",
                    MigrationStoragePaths.owned(context, MigrationSnapshot.storage(context)),
                )
                putBoolean(
                    "storageExists",
                    MigrationSnapshot.storage(context)?.let {
                        com.hippo.unifile.UniFile.fromUri(context, Uri.parse(it))?.exists()
                    } == true,
                )
                putString("consent", MigrationConsentActivity::class.java.name)
            }
        }
        authorized(arg)
        return when (method) {
            "storageGrant" -> {
                // Binder calls arrive under the receiver UID; Android requires the grantor's identity.
                val identity = Binder.clearCallingIdentity()
                try {
                    Bundle().apply {
                        putBoolean(
                            "delegated",
                            MigrationSharedStorage.delegate(
                                context,
                                BuildConfig.PRODUCTION_APPLICATION_ID,
                                MigrationSnapshot.storage(context),
                            ),
                        )
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
            }
            "prepare" -> {
                if (MigrationState.state(context).optBoolean("refresh")) {
                    check(result.getString("phase") != "preparing")
                    prepared?.file?.parentFile?.deleteRecursively()
                    prepared = null
                    result = Bundle()
                    MigrationState.update(context) { it.remove("refresh") }
                }
                if (result.getString("phase") !in setOf("preparing", "ready")) {
                    result = Bundle().apply { putString("phase", "preparing") }
                    cancelled.set(false)
                    preparing.set(true)
                    MigrationState.update(context) { it.put("preparedBytes", 0L) }
                    executor.execute {
                        try {
                            // A fresh worker sees current preferences and databases on every retry.
                            val manager = context.getSystemService(android.app.ActivityManager::class.java)
                            manager.runningAppProcesses.orEmpty().filter {
                                it.uid == android.os.Process.myUid() &&
                                    it.processName == "${context.packageName}:migration_snapshot"
                            }.forEach { android.os.Process.killProcess(it.pid) }
                            val exported = context.contentResolver.call(
                                Uri.parse("content://${context.packageName}.migration.snapshot"),
                                "prepare",
                                arg,
                                null,
                            )
                            if (exported?.getBoolean("ready") != true) {
                                throw MigrationFailure.Remote(exported?.getString("error") ?: "prepare:Unavailable")
                            }
                            val directory = java.io.File(MigrationState.directory(context), "export")
                            val metadata = org.json.JSONObject(java.io.File(directory, "ready.json").readText())
                            prepared = PreparedMigration(
                                java.io.File(directory, "snapshot.encrypted"),
                                metadata.getString("sha256"),
                                kotlinx.serialization.json.Json.decodeFromString(
                                    MigrationManifest.serializer(),
                                    metadata.getString("manifest"),
                                ),
                            )
                            check(!cancelled.get())
                            val snapshot = checkNotNull(prepared)
                            val saved = org.json.JSONObject(
                                java.io.File(snapshot.file.parentFile, "ready.json").readText(),
                            )
                            result = ready(snapshot, saved.getLong("plainBytes"))
                        } catch (error: Exception) {
                            java.io.File(MigrationState.directory(context), "export").deleteRecursively()
                            prepared = null
                            MigrationState.unfreeze(context)
                            MigrationKeepAliveService.stop(context)
                            MigrationFailure.record(context, "prepare", error)
                            result =
                                Bundle().apply {
                                    putString("phase", "failed")
                                    putString("error", MigrationFailure.code("prepare", error))
                                }
                        } finally {
                            preparing.set(false)
                            MigrationState.unfreeze(context)
                            if (cancelled.get()) MigrationKeepAliveService.stop(context)
                        }
                    }
                }
                Bundle(result)
            }
            "status" -> Bundle(result).apply {
                if (getString("phase") ==
                    "preparing"
                ) {
                    putLong("bytes", MigrationState.state(context).optLong("preparedBytes"))
                }
            }
            "abort" -> {
                cancelled.set(true)
                MigrationState.update(context) {
                    it.remove("grant")
                    it.remove("grantUntil")
                }
                if (!preparing.get()) {
                    MigrationState.unfreeze(context)
                    MigrationKeepAliveService.stop(context)
                }
                Bundle.EMPTY
            }
            "finish" -> {
                check(result.getString("phase") != "preparing")
                prepared?.file?.parentFile?.deleteRecursively()
                prepared = null
                result = Bundle()
                MigrationState.update(context) {
                    it.remove("grant")
                    it.remove("grantUntil")
                    it.remove("frozenUntil")
                    it.remove("frozenElapsed")
                    it.put("pauseTransferredSync", true)
                    it.put("sourceTransferComplete", true)
                }
                MigrationKeepAliveService.stop(context)
                Bundle.EMPTY
            }
            else -> throw IllegalArgumentException("Unknown migration operation")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r" && uri.pathSegments.size == 2 && uri.pathSegments.first() == "snapshot")
        authorized(uri.lastPathSegment)
        check(result.getString("phase") == "ready")
        val file = checkNotNull(prepared).file
        val offset = uri.getQueryParameter("offset")?.toLongOrNull() ?: 0L
        require(offset in 0..result.getLong("archiveBytes"))
        val pipe = ParcelFileDescriptor.createReliablePipe()
        executor.execute {
            val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            try {
                MigrationCrypto.decrypt(file.inputStream()).use { input ->
                    var remaining = offset
                    val buffer = ByteArray(256 * 1024)
                    var heartbeat = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val skipped = minOf(remaining, count.toLong()).toInt()
                        remaining -= skipped
                        if (skipped < count) output.write(buffer, skipped, count - skipped)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - heartbeat >
                            1000
                        ) {
                            heartbeat = now
                            check(!cancelled.get()) { "Migration cancelled" }
                        }
                    }
                    check(remaining == 0L)
                }
            } catch (error: Exception) {
                runCatching { pipe[1].closeWithError("Migration stream failed") }
            } finally {
                runCatching { output.close() }
            }
        }
        return pipe[0]
    }

    override fun getType(uri: Uri) = "application/x-nyanime-migration"
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    companion object {
        fun grant(context: android.content.Context): String {
            val token = UUID.randomUUID().toString()
            check(!MigrationState.frozen(context)) { "Snapshot is still preparing" }
            MigrationState.update(context) {
                it.put("grant", token).put("refresh", true)
                    .put("grantUntil", System.currentTimeMillis() + 10 * 60_000)
            }
            return token
        }
    }
}
