package eu.kanade.tachiyomi.data.recovery

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.entries.anime.interactor.GetAnimeFavorites
import tachiyomi.domain.entries.manga.interactor.GetMangaFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RecoveryHealthMonitor(private val context: Context) : DefaultLifecycleObserver {
    private var pending: Job? = null
    override fun onStart(owner: LifecycleOwner) {
        pending?.cancel()
        if (!RecoveryAccess(context).hasGrants()) return
        pending = owner.lifecycleScope.launch(Dispatchers.IO) {
            delay(30_000)
            runCatching {
                Injekt.get<GetAnimeFavorites>().await()
                Injekt.get<GetMangaFavorites>().await()
                RecoveryJournal.record(context, "healthy")
            }
        }
    }
    override fun onStop(owner: LifecycleOwner) {
        pending?.cancel()
    }
}
