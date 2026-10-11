package it.lorelore.nyanime.attention

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun AttentionReveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val preference = Injekt.get<PlayerPreferences>().reduceMotion()
    val reduced by preference.changes().collectAsState(preference.get())
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduced) EnterTransition.None else ModernMotion.enter(),
        exit = if (reduced) ExitTransition.None else ModernMotion.exit(),
    ) { content() }
}
