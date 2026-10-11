package it.lorelore.nyanime.backup.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Explicit, non-exported notification action. Cancels this attempt, not future automatic backups. */
class RemoteBackupCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val provider = intent.getStringExtra("provider")?.let {
            runCatching { RemoteBackupProvider.valueOf(it) }.getOrNull()
        } ?: return
        val generation = intent.getStringExtra("generation") ?: return
        val workId = intent.getStringExtra("work") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8000) {
                    RemoteBackupWorker.cancelAttempt(context.applicationContext, provider, generation, workId)
                }
            } catch (_: Exception) {
                // No credentials, remote URLs or server messages enter broadcasts or logs.
            } finally {
                pending.finish()
            }
        }
    }
}
