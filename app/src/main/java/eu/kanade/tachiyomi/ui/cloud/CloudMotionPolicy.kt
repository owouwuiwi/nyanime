package eu.kanade.tachiyomi.ui.cloud

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val LocalCloudMotionEnabled = compositionLocalOf<Boolean?> { null }

/** Keep Cloud's existing motion preference scoped to its Activity. */
@Composable
internal fun CloudMotionProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduced by remember { Injekt.get<PlayerPreferences>().reduceMotion() }.collectAsState()
    var systemEnabled by remember(context) { mutableStateOf(systemAnimationsEnabled(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemEnabled = systemAnimationsEnabled(context)
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
            true
        }.getOrDefault(false)
        systemEnabled = systemAnimationsEnabled(context)
        onDispose { if (registered) runCatching { resolver.unregisterContentObserver(observer) } }
    }
    CompositionLocalProvider(LocalCloudMotionEnabled provides (!reduced && systemEnabled), content = content)
}

@Composable
internal fun cloudMotionEnabled(): Boolean = LocalCloudMotionEnabled.current
    ?: (eu.kanade.presentation.motion.appMotionEnabled() && ValueAnimator.areAnimatorsEnabled())

private fun systemAnimationsEnabled(context: Context): Boolean = runCatching {
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    !scale.isFinite() || scale > 0f
}.getOrElse { ValueAnimator.areAnimatorsEnabled() }
