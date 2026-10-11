package eu.kanade.tachiyomi.ui.cloud

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cloud.CloudPreferences
import tachiyomi.presentation.core.util.collectAsState

internal val LocalCloudSetupExit = staticCompositionLocalOf<() -> Unit> { {} }

/** Optional, local first-launch choice. Telegram is not started until the user chooses it. */
@Composable
fun InitialCloudSetupGate(onReady: () -> Unit, content: @Composable () -> Unit) {
    val preferences = remember { CloudPreferences() }
    val complete by preferences.introductionComplete().collectAsState()
    // An account already configured before this introduction needs no new onboarding.
    if (complete || preferences.enabled().get()) {
        LaunchedEffect(Unit) { if (!complete) preferences.introductionComplete().set(true) }
        content()
        return
    }
    var connecting by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current as? ComponentActivity
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    LaunchedEffect(dark) {
        onReady()
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    if (connecting) {
        CompositionLocalProvider(LocalCloudSetupExit provides { connecting = false }) {
            Navigator(CloudScreen(initialSetup = true)) { navigator ->
                BackHandler { if (navigator.canPop) navigator.pop() else connecting = false }
                DefaultNavigatorScreenTransition(navigator)
            }
        }
    } else {
        BackHandler { preferences.introductionComplete().set(true) }
        CloudIntroductionContent(
            onConnect = { connecting = true },
            onSkip = { preferences.introductionComplete().set(true) },
        )
    }
}

@Composable
internal fun CloudIntroductionContent(onConnect: () -> Unit, onSkip: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Scaffold(
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.widthIn(max = 640.dp)) {
                        CloudActionButton(stringResource(R.string.cloud_intro_connect), true, onClick = onConnect)
                        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.cloud_intro_later))
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                AsyncImage(
                    model = if (dark) R.raw.nyanime_wordmark_dark else R.raw.nyanime_wordmark_light,
                    contentDescription = "Nyanime",
                    modifier = Modifier.width(160.dp).height(40.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            item { Column(Modifier.widthIn(max = 640.dp)) { CloudWelcome() } }
            item {
                Text(
                    stringResource(R.string.cloud_intro_restore_hint),
                    Modifier.widthIn(max = 640.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Column(Modifier.widthIn(max = 640.dp)) { CloudPrivacy() } }
        }
    }
}
