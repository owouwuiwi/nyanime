package eu.kanade.tachiyomi.ui.rin

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.theme.LocalDarkTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.rin.RinMigration
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionLoader
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader

@Composable
fun RinMigrationGate(onReady: () -> Unit, content: @Composable () -> Unit) {
    val motion = appMotionEnabled()
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val background = colors.background
    val surface = colors.surfaceContainerLow
    val muted = colors.onSurfaceVariant
    val accent = colors.primary
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val repository = remember { RinRepository.get(context) }
    val migration = remember { RinMigration.get(context, repository) }
    val state by migration.state.collectAsState()
    val progress by repository.progress.collectAsState()
    val requested by repository.migrationRequests.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(requested, revision) {
        if (state.phase == "checking" || state.phase == "open") migration.check()
    }
    if (state.phase == "open" || state.phase == "checking") {
        content()
        return
    }
    LaunchedEffect(dark) {
        onReady()
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    BackHandler { activity?.finish() }
    val remaining =
        remember(state, revision) { state.entries.filter { repository.legacyPresent(it.manifest.packageName) } }
    val uninstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    LaunchedEffect(state.phase, remaining.size) {
        if (state.phase == "remove" && remaining.isEmpty()) migration.configure()
    }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(background).safeDrawingPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = if (dark) R.raw.nyanime_wordmark_dark else R.raw.nyanime_wordmark_light,
                contentDescription = "Nyanime",
                modifier = Modifier.weight(1f, fill = false).width(148.dp).height(36.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "RIN",
                Modifier.background(surface, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                color = colors.onSurface,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        AnimatedContent(
            targetState = state.phase,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                (
                    if (motion) {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(140))
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                    ).using(null)
            },
            label = "rinMigrationPhase",
        ) { phase ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(
                        when (phase) {
                            "intro" -> "Le tue estensioni,\nora RIN."
                            "prepare" -> "Prepariamo il passaggio."
                            "remove" -> "Rimuoviamo le vecchie versioni."
                            "configure" -> "Configuriamo RIN."
                            else -> "Tutto pronto."
                        },
                        color = colors.onSurface,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when (phase) {
                            "intro" -> "Un solo passaggio. Poi si aggiornano da sole."
                            "prepare" -> "Scarichiamo e verifichiamo RIN prima di rimuovere le versioni attuali."
                            "remove" -> "Nyanime ti guida, una alla volta."
                            "configure" -> "Ritroverai tutte le tue impostazioni."
                            else -> "Le tue estensioni sono Nyanime RIN."
                        },
                        color = muted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (phase == "intro") {
                    Benefit(
                        Icons.Outlined.Update,
                        "Novità e correzioni automatiche",
                        "Gli aggiornamenti delle RIN installate arrivano dal catalogo, senza installazioni manuali.",
                    )
                    Benefit(
                        Icons.Outlined.Extension,
                        "Tutto dentro Nyanime",
                        "Aggiungi, aggiorni e rimuovi le fonti dal tuo Store.",
                    )
                    Benefit(
                        Icons.Outlined.CloudDownload,
                        "Niente app separate",
                        "Le estensioni RIN vivono direttamente nell’app.",
                    )
                    Column(
                        Modifier.fillMaxWidth().background(surface, RoundedCornerShape(16.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Benefit(
                            Icons.Outlined.Security,
                            "La tua libreria resta al suo posto",
                            "Titoli, progressi, impostazioni e collegamenti vengono conservati.",
                        )
                    }
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text("Quali aggiornamenti riceverò? ${if (expanded) "−" else "+"}", color = colors.onSurface)
                    }
                    if (expanded) {
                        Text(
                            "Tutte le novità pubblicate dal catalogo per le tue estensioni RIN compatibili. Gli aggiornamenti dell’app restano separati. Le altre fonti non vengono installate automaticamente. Durante visione e lettura gli aggiornamenti aspettano.",
                            color = muted,
                        )
                    }
                    Text(
                        if (state.entries.size == 1) {
                            "1 estensione da convertire"
                        } else {
                            "${state.entries.size} estensioni da convertire"
                        },
                        color = muted,
                    )
                } else {
                    if (phase in setOf("remove", "configure", "complete")) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("Rimuovi", "Configura", "Entra").forEachIndexed { index, label ->
                                val current = when (phase) {
                                    "remove" -> 0
                                    "configure" -> 1
                                    else -> 2
                                }
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        Modifier.size(32.dp).background(
                                            if (index <= current) colors.onSurface else surface,
                                            RoundedCornerShape(50),
                                        ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            if (index < current) "✓" else "${index + 1}",
                                            color = if (index <= current) colors.surface else muted,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    Text(
                                        label,
                                        color = if (index <= current) colors.onSurface else muted,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().background(surface, RoundedCornerShape(16.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        state.entries.forEach { entry ->
                            val prepared = state.prepared.any { it.manifest.packageName == entry.manifest.packageName }
                            val installed = repository.storage.record(entry.manifest.packageName) != null
                            val removed = remaining.none { it.manifest.packageName == entry.manifest.packageName }
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    entry.manifest.name,
                                    color = colors.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    when (phase) {
                                        "prepare" -> if (prepared) "Verificata ✓" else "In attesa"
                                        "remove" -> if (removed) "Rimossa ✓" else "Da rimuovere"
                                        else -> if (installed) "RIN attiva ✓" else "In attesa"
                                    },
                                    color = muted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (progress?.first == entry.manifest.packageName) {
                                val value = progress?.second
                                if (value !=
                                    null
                                ) {
                                    LinearProgressIndicator(
                                        progress = { value },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = colors.onSurface,
                                        trackColor = background,
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        Modifier.fillMaxWidth(),
                                        color = colors.onSurface,
                                        trackColor = background,
                                    )
                                }
                            }
                        }
                    }
                    if (phase ==
                        "remove"
                    ) {
                        Text(
                            "Pacchetti RIN pronti e verificati. Android ti chiederà conferma per rimuovere la vecchia versione.",
                            color = muted,
                        )
                    }
                    if (phase ==
                        "complete"
                    ) {
                        Benefit(
                            Icons.Outlined.CheckCircle,
                            "Aggiornamenti automatici attivi",
                            "Novità e correzioni arriveranno direttamente qui, quando non stai guardando o leggendo.",
                        )
                    }
                    if (state.error) {
                        Text(
                            "${if (phase == "prepare") "Download interrotto" else "Configurazione interrotta"}. Puoi riprovare senza perdere i tuoi dati.",
                            color = accent,
                        )
                    }
                }
            }
        }
        Button(
            enabled = !state.working,
            onClick = {
                when (state.phase) {
                    "intro", "prepare" -> migration.prepare()
                    "remove" -> remaining.firstOrNull()?.let { entry ->
                        val pkg = entry.manifest.packageName
                        if (runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess) {
                            uninstall.launch(
                                Intent(
                                    Intent.ACTION_DELETE,
                                    "package:$pkg".toUri(),
                                ).putExtra(Intent.EXTRA_RETURN_RESULT, true),
                            )
                        } else {
                            AnimeExtensionLoader.uninstallPrivateExtension(context, pkg)
                            MangaExtensionLoader.uninstallPrivateExtension(context, pkg)
                            revision++
                        }
                    }
                    "configure" -> migration.configure()
                    "complete" -> migration.enter()
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.onSurface,
                contentColor = colors.surface,
                disabledContainerColor = surface,
                disabledContentColor = muted,
            ),
        ) {
            Text(
                when (state.phase) {
                    "intro" -> "Passa a RIN"
                    "prepare" -> if (state.error) "Riprova download" else "Preparazione…"
                    "remove" -> "Rimuovi e continua"
                    "configure" -> if (state.error) "Riprendi configurazione" else "Configurazione…"
                    else -> "Apri Nyanime"
                },
                fontWeight = FontWeight.Bold,
            )
        }
        if (state.phase ==
            "intro"
        ) {
            Text(
                "Passaggio necessario per continuare.",
                Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
                color = muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TextButton(onClick = {
            activity?.finish()
        }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Esci dall’app", color = muted) }
    }
}

@Composable
private fun Benefit(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = colors.onSurface)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                title,
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
