package it.lorelore.nyanime.novel.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/** A delayed action can pause only the exact download displayed in its notification. */
class NovelDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val book =
            intent.getStringExtra("book")?.takeIf { it.matches(Regex("b_[a-f0-9]{64}")) } ?: return
        val job =
            intent.getStringExtra("job")?.takeIf {
                runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
            } ?: return
        val pending = goAsync()
        scope.launch {
            try {
                Injekt.get<NovelDownloads>().pauseIfCurrent(book, job)
            } catch (_: Exception) {
                android.util.Log.w("NovelDownloads", "Could not complete a download pause")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
