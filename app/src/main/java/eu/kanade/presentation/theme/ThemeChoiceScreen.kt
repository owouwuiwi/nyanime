package eu.kanade.presentation.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.motion.ModernMotion
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

/** Stateless choice UI, shared by first launch and Appearance settings. */
@Composable
fun ThemeChoiceScreen(
    selectedMode: ThemeMode,
    saving: Boolean,
    error: Boolean,
    onSelect: (ThemeMode) -> Unit,
    onContinue: () -> Unit,
    animationsEnabled: Boolean = true,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    if (error) {
                        Text(
                            stringResource(AYMR.strings.nyanime_theme_save_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = onContinue,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            stringResource(
                                if (saving) AYMR.strings.nyanime_theme_saving else AYMR.strings.nyanime_theme_continue,
                            ),
                        )
                    }
                }
            }
        },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NyanimeMark(Modifier.size(44.dp))
                    Text(
                        stringResource(AYMR.strings.nyanime_theme_choice_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            item { ThemeModeCards(selectedMode, onSelect, enabled = !saving, animationsEnabled = animationsEnabled) }
            item {
                Text(
                    stringResource(AYMR.strings.nyanime_theme_change_later),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ThemeModeCards(
    selectedMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    enabled: Boolean = true,
    animationsEnabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM).forEach { mode ->
            val selected = selectedMode == mode
            val duration = if (animationsEnabled) ModernMotion.RESIZE_MILLIS else 0
            val borderColor by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                tween(duration),
                label = "themeChoiceBorder",
            )
            val cardColor by animateColorAsState(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                tween(duration),
                label = "themeChoiceSurface",
            )
            val checkAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(duration), label = "themeChoiceCheck")
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        if (selected) 2.dp else 1.dp,
                        borderColor,
                        RoundedCornerShape(20.dp),
                    )
                    .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(mode) }),
                shape = RoundedCornerShape(20.dp),
                color = cardColor,
            ) {
                Row(
                    Modifier.padding(14.dp).heightIn(min = 86.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ThemeMiniature(mode, Modifier.width(82.dp).height(86.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(
                                when (mode) {
                                    ThemeMode.DARK -> AYMR.strings.nyanime_theme_dark
                                    ThemeMode.LIGHT -> AYMR.strings.nyanime_theme_light
                                    ThemeMode.SYSTEM -> AYMR.strings.nyanime_theme_system
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(
                                when (mode) {
                                    ThemeMode.DARK -> AYMR.strings.nyanime_theme_dark_description
                                    ThemeMode.LIGHT -> AYMR.strings.nyanime_theme_light_description
                                    ThemeMode.SYSTEM -> AYMR.strings.nyanime_theme_system_description
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Check,
                            null,
                            Modifier.alpha(checkAlpha),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeMiniature(mode: ThemeMode, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(12.dp))) {
        val themes = if (mode == ThemeMode.SYSTEM) listOf(false, true) else listOf(mode == ThemeMode.DARK)
        themes.forEach { dark ->
            TachiyomiPreviewTheme(darkTheme = dark) {
                Column(
                    Modifier.weight(1f).fillMaxSize().background(MaterialTheme.colorScheme.background).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NyanimeMark(Modifier.size(20.dp))
                    Box(
                        Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    )
                    Box(
                        Modifier.fillMaxWidth().height(
                            3.dp,
                        ).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)),
                    )
                    Box(
                        Modifier.fillMaxWidth(
                            0.65f,
                        ).height(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
                    )
                }
            }
        }
    }
}
