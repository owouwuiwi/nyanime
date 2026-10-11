package eu.kanade.tachiyomi.ui.more

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import eu.kanade.presentation.more.UpdateChannelChoice
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.theme.NyanimeWordmark
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.addon.InitialAddonIntroductionGate
import eu.kanade.tachiyomi.ui.theme.InitialThemeChoiceGate
import eu.kanade.tachiyomi.util.system.updaterEnabled
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.AppUpdatePreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Complete first-launch explanations before the navigator consumes the launch intent. */
@Composable
fun InitialAppSetupGate(onReady: () -> Unit, content: @Composable () -> Unit) {
    val base = remember { Injekt.get<eu.kanade.domain.base.BasePreferences>() }
    val complete by base.shownOnboardingFlow().collectAsState()
    if (!complete) {
        LaunchedEffect(Unit) { onReady() }
        cafe.adriel.voyager.navigator.Navigator(OnboardingScreen(initialSetup = true)) { navigator ->
            eu.kanade.presentation.util.DefaultNavigatorScreenTransition(navigator)
        }
        return
    }
    eu.kanade.tachiyomi.ui.cloud.InitialCloudSetupGate(onReady) {
        InitialThemeChoiceGate(onReady) {
            InitialUpdateChoiceGate(onReady) {
                InitialAddonIntroductionGate(onReady) {
                    eu.kanade.tachiyomi.ui.rin.RinMigrationGate(onReady, content)
                }
            }
        }
    }
}

/** Keep launch intents untouched until the one-time choice has been confirmed. */
@Composable
fun InitialUpdateChoiceGate(onReady: () -> Unit, content: @Composable () -> Unit) {
    val preferences = remember { AppUpdatePreferences(Injekt.get()) }
    val complete by preferences.choiceComplete.collectAsState()
    if (!updaterEnabled || complete) {
        content()
        return
    }
    var selectedKey by rememberSaveable { mutableStateOf(preferences.channel().key) }
    var entered by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val motion = appMotionEnabled()
    val alpha by animateFloatAsState(
        if (!motion || entered) 1f else 0f,
        tween(if (motion) ModernMotion.PAGE_MILLIS else 0),
        label = "updateChoiceAppearance",
    )
    val activity = LocalContext.current as? ComponentActivity
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    BackHandler { activity?.finish() }
    LaunchedEffect(dark) {
        onReady()
        entered = true
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    Scaffold(
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
        containerColor = colors.background,
        bottomBar = {
            Surface(color = colors.background) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            if (!confirming) {
                                confirming = true
                                preferences.confirm(UpdateChannel.fromKey(selectedKey))
                            }
                        },
                        enabled = !confirming,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (dark) colors.primaryContainer else colors.primary,
                            contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary,
                        ),
                    ) {
                        Text(stringResource(R.string.app_update_confirm), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        stringResource(R.string.app_update_once),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                SubcomposeAsyncImage(
                    model = if (dark) R.raw.nyanime_wordmark_dark else R.raw.nyanime_wordmark_light,
                    contentDescription = "Nyanime",
                    modifier = Modifier.widthIn(max = 148.dp).height(36.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    loading = { NyanimeWordmark() },
                    error = { NyanimeWordmark() },
                )
            }
            item {
                Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                    Text(
                        stringResource(R.string.app_update_version_chip, BuildConfig.VERSION_NAME),
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.app_update_choice_title),
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.app_update_choice_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            item { UpdateChannelChoice(UpdateChannel.fromKey(selectedKey)) { selectedKey = it.key } }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Settings, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                    Text(
                        stringResource(R.string.app_update_change_later),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
