package eu.kanade.tachiyomi.data.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume

/** No addon means no export, foreign process or network work on the ordinary update path. */
object PreventiveRecoveryBackup {
    suspend fun create(context: Context, onPhase: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val recipients = RecoveryAccess(context).approved()
        if (recipients.isEmpty()) return@withContext false
        val descriptor =
            requireNotNull(
                context.contentResolver.call(
                    Uri.parse("content://${context.packageName}.recovery"),
                    "describe",
                    null,
                    null,
                ),
            )
        val file = File.createTempFile("safety-copy-", ".nyabk", context.cacheDir)
        try {
            onPhase(0)
            BackupCreator(context, false).backup(Uri.fromFile(file), BackupOptions.complete()) { phase ->
                onPhase(if (phase == "verifying") 2 else 1)
            }
            val hash = RecoveryBridgeProvider.sha256(file)
            recipients.forEach { addon ->
                check(
                    RecoveryAccess(context).approved().any {
                        it.packageName == addon.packageName
                    },
                ) { "Recovery access was revoked" }
                val service = context.packageManager.queryIntentServices(
                    Intent("nyanime.recovery.VAULT_BIND").setPackage(addon.packageName),
                    0,
                )
                    .map { it.serviceInfo }.single { it.enabled && it.exported && it.permission == null }
                val connection = object : ServiceConnection {
                    var continuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null
                    override fun onServiceConnected(
                        name: ComponentName?,
                        binder: IBinder?,
                    ) {
                        continuation?.takeIf { it.isActive }?.resume(Unit)
                        continuation =
                            null
                    }
                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
                var bound = false
                var slotId: String? = null
                val vault = Uri.parse("content://${addon.packageName}.recovery")
                try {
                    withTimeout(15_000) {
                        suspendCancellableCoroutine { continuation ->
                            connection.continuation = continuation
                            bound =
                                context.bindService(
                                    Intent().setComponent(ComponentName(addon.packageName, service.name)),
                                    connection,
                                    Context.BIND_AUTO_CREATE,
                                )
                            check(bound) { "Cannot connect to the recovery vault" }
                        }
                    }
                    val slot = requireNotNull(
                        context.contentResolver.call(
                            vault,
                            "prepare",
                            null,
                            Bundle().apply {
                                putBundle("host", descriptor)
                                putString("sha256", hash)
                                putLong("bytes", file.length())
                                putString("kind", "preventive")
                            },
                        ),
                    )
                    val id = requireNotNull(slot.getString("id"))
                    slotId = id
                    val destination = Uri.parse(requireNotNull(slot.getString("uri")))
                    require(destination.authority == vault.authority)
                    onPhase(1)
                    requireNotNull(context.contentResolver.openOutputStream(destination, "w")).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                    onPhase(2)
                    withTimeout(600_000) {
                        while (true) {
                            val status = requireNotNull(context.contentResolver.call(vault, "status", id, null))
                            when (status.getString("phase")) {
                                "verified" -> {
                                    check(status.getString("sha256") == hash)
                                    break
                                }
                                "failed" -> error("The recovery vault did not verify the safety copy")
                            }
                            delay(250)
                        }
                    }
                } finally {
                    slotId?.let { runCatching { context.contentResolver.call(vault, "release", it, null) } }
                    if (bound) context.unbindService(connection)
                }
            }
            true
        } finally {
            file.delete()
        }
    }
}
