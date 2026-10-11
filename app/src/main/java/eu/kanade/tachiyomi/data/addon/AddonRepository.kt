package eu.kanade.tachiyomi.data.addon

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.more.AboutActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AddonRepository private constructor(private val context: Context) {
    val registry = AddonRegistry(context)
    val catalogue by lazy { AddonExtensionCatalogue(context, Injekt.get<NetworkHelper>()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    refreshInstalled()
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun refreshInstalled() {
        scope.launch { registry.reload() }
    }

    /** Reinspect immediately before launch: revoked trust or replaced packages cannot use stale UI state. */
    suspend fun open(packageName: String, activityContext: Context): Boolean {
        registry.reload()
        val addon = registry.state.value.singleOrNull { it.packageName == packageName && it.usable } ?: return false
        return kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            runCatching {
                var current = activityContext
                while (current is ContextWrapper && current !is Activity) current = current.baseContext
                val activity = current as? Activity ?: return@withContext false
                if (activity.isFinishing || activity.isDestroyed) return@withContext false
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val back = PendingIntent.getActivity(
                    context,
                    401,
                    Intent(
                        context,
                        MainActivity::class.java,
                    ).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    flags,
                )
                val about = PendingIntent.getActivity(context, 402, Intent(context, AboutActivity::class.java), flags)
                activity.startActivity(
                    Intent(AddonRegistry.OPEN).setClassName(packageName, requireNotNull(addon.entryClass))
                        .addFlags(if (addon.standalone) Intent.FLAG_ACTIVITY_NEW_TASK else 0)
                        .putExtra("nyanime.addon.protocol", addon.api)
                        .putExtra("nyanime.addon.return", back)
                        .putExtra("nyanime.addon.about", about)
                        .putExtra("nyanime.addon.host", context.packageName)
                        .putExtra(
                            "nyanime.addon.recovery",
                            if (addon.dataRecovery) "content://${context.packageName}.recovery" else null,
                        )
                        .putExtra(
                            "nyanime.addon.appearance",
                            if (addon.sharesAppearance) "content://${context.packageName}.addons.appearance" else null,
                        ),
                )
            }.isSuccess
        }
    }

    companion object {
        @Volatile private var instance: AddonRepository? = null
        fun get(context: Context): AddonRepository = instance ?: synchronized(this) {
            instance ?: AddonRepository(context.applicationContext).also { instance = it }
        }
    }
}
