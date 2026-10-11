package eu.kanade.tachiyomi.data.migration

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import eu.kanade.tachiyomi.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object MigrationState {
    fun directory(context: Context) = File(context.noBackupFilesDir, "identity-migration").apply { mkdirs() }
    fun state(context: Context): JSONObject = runCatching {
        JSONObject(
            AtomicFile(File(directory(context), "state.json")).openRead().use {
                it.readBytes().decodeToString()
            },
        )
    }.getOrDefault(JSONObject())

    private fun save(context: Context, state: JSONObject) {
        val file = AtomicFile(File(directory(context), "state.json"))
        val stream = file.startWrite()
        try {
            stream.write(state.toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized fun update(context: Context, action: (JSONObject) -> Unit) {
        java.io.RandomAccessFile(File(directory(context), "state.lock"), "rw").use { file ->
            file.channel.lock().use {
                val current = state(context)
                action(current)
                save(context, current)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun verified(context: Context, packageName: String, fingerprint: String): Boolean = runCatching {
        val flags = if (Build.VERSION.SDK_INT >=
            28
        ) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageInfo(packageName, flags)
        val signers = if (Build.VERSION.SDK_INT >=
            28
        ) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        signers.size == 1 &&
            MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
                .joinToString("") { "%02X".format(it) } == fingerprint
    }.getOrDefault(false)

    fun legacyPresent(context: Context) = verified(
        context,
        BuildConfig.LEGACY_APPLICATION_ID,
        BuildConfig.LEGACY_CERT_SHA256,
    )
    fun shouldOffer(context: Context) = context.packageName == BuildConfig.PRODUCTION_APPLICATION_ID &&
        !state(context).optBoolean("complete") &&
        !state(context).optBoolean("declined") &&
        legacyPresent(context)

    fun mayRemoveLegacy(context: Context): Boolean = context.packageName == BuildConfig.PRODUCTION_APPLICATION_ID &&
        state(context).let {
            MigrationRemovalPolicy.allowed(it.optBoolean("complete"), it.has("transaction"), legacyPresent(context))
        }

    fun deferredStartup(context: Context): Boolean = frozen(context) ||
        (context.applicationContext as? eu.kanade.tachiyomi.App)?.migrationStartupDeferred == true

    fun frozen(context: Context): Boolean = state(context).let {
        (it.has("transaction") && !it.optBoolean("complete")) ||
            MigrationLease.active(
                it.optLong("frozenUntil"),
                it.optLong("frozenElapsed"),
                System.currentTimeMillis(),
                android.os.SystemClock.elapsedRealtime(),
            )
    }
    fun renew(context: Context) = update(context) {
        it.put("frozenUntil", System.currentTimeMillis() + MigrationLease.DURATION)
        it.put("frozenElapsed", android.os.SystemClock.elapsedRealtime() + MigrationLease.DURATION)
    }
    fun unfreeze(context: Context) = update(context) {
        it.remove("frozenUntil")
        it.remove("frozenElapsed")
    }

    /** Only our own UID is eligible; never stops other applications. WAL remains recoverable. */
    fun stopOwnOtherProcesses(context: Context, preserveMigration: Boolean = false) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses.orEmpty().filter {
            it.uid == android.os.Process.myUid() &&
                it.pid != android.os.Process.myPid() &&
                (!preserveMigration || !it.processName.startsWith("${context.packageName}:migration"))
        }.forEach { android.os.Process.killProcess(it.pid) }
    }
}
