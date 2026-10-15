package eu.kanade.tachiyomi.data.rin

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.cast.CastController
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object RinSessions {
    private val activities = mutableSetOf<Activity>()
    val busy = MutableStateFlow(false)
    private val playbackOwners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    private val playbackBusy = MutableStateFlow(false)

    internal fun enterPlayback(owner: Any) {
        playbackOwners += owner
        playbackBusy.value = playbackOwners.isNotEmpty()
        busy.value = true
    }

    internal fun leavePlayback(owner: Any) {
        playbackOwners -= owner
        playbackBusy.value = playbackOwners.isNotEmpty()
    }

    fun start(application: Application) {
        val repository = RinRepository.get(application)
        val casting = CastController.sessionActive
        val activityBusy = MutableStateFlow(false)
        var startedActivities = 0
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is PlayerActivity ||
                    activity is eu.kanade.tachiyomi.ui.tv.TvPlayerActivity ||
                    activity is ReaderActivity ||
                    activity is eu.kanade.tachiyomi.data.addon.RinAddonActivity
                ) {
                    activities += activity
                    activityBusy.value = true
                    busy.value = true
                }
            }
            override fun onActivityDestroyed(activity: Activity) {
                activities -= activity
                activityBusy.value = activities.isNotEmpty()
                busy.value = activityBusy.value || casting.value || playbackBusy.value
            }
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ ==
                    0
                ) {
                    repository.scope.launch {
                        if (!eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository.get(
                                application,
                            ).checkImportedUpdates()
                        ) {
                            retry(application)
                        }
                    }
                }
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        repository.scope.launch {
            combine(activityBusy, casting, playbackBusy) { reading, activeCast, playing ->
                reading ||
                    activeCast ||
                    playing
            }
                .collectLatest {
                    busy.value = it
                    if (!it) {
                        try {
                            if (!eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository.get(
                                    application,
                                ).checkImportedUpdates()
                            ) {
                                retry(application)
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            // A catalogue failure cannot affect the host or its player.
                        }
                    }
                }
        }
        WorkManager.getInstance(application).enqueueUniquePeriodicWork(
            "rin-updates",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RinUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** Persistent retries survive process death; no polling runs in the player or reader. */
    internal fun retry(context: android.content.Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "rin-update-retry",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RinUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build(),
        )
    }
}

class RinUpdateWorker(context: android.content.Context, parameters: WorkerParameters) : CoroutineWorker(
    context,
    parameters,
) {
    override suspend fun doWork(): Result = try {
        if (eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository.get(
                applicationContext,
            ).checkImportedUpdates(force = true)
        ) {
            Result.success()
        } else {
            Result.retry()
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Exception) {
        Result.retry()
    }
}
