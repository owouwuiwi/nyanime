package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.community.CommunityManager
import eu.kanade.tachiyomi.data.community.CommunityState
import eu.kanade.tachiyomi.data.community.SyncRecord
import eu.kanade.tachiyomi.ui.community.AssociationSheet
import eu.kanade.tachiyomi.ui.community.PairingSheet
import eu.kanade.tachiyomi.ui.community.RecoverySheet
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

/** Opening the introduction does not create an identity, an outbox, or a connection. */
object SettingsPersonalSyncScreen : Screen() {
    @Composable
    override fun Content() {
        if (!BuildConfig.PERSONAL_SYNC_ENABLED) {
            // A saved navigation stack from an older APK must not reopen the sync runtime.
            SettingsMainScreen.Content()
            return
        }
        val context = LocalContext.current
        val preferences = remember { Injekt.get<BasePreferences>() }
        val configured by preferences.personalSyncEnabled().collectAsState()
        val incognito by preferences.incognitoMode().collectAsState()
        var manager by remember {
            mutableStateOf(
                if (configured ||
                    CommunityManager.hasPersonalIdentity(context)
                ) {
                    CommunityManager.personal(context)
                } else {
                    null
                },
            )
        }
        val state = manager?.state?.collectAsState()?.value ?: CommunityState(syncEnabled = false)
        val back = LocalBackPress.currentOrThrow
        val scope = rememberCoroutineScope()
        var sheet by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf<SyncRecord?>(null) }
        var opening by remember { mutableStateOf<String?>(null) }
        var localError by remember { mutableStateOf<String?>(null) }
        fun setup(action: String) {
            manager = CommunityManager.personal(context)
            sheet = action
            if (action == "create") manager?.createPersonal()
        }
        Scaffold(topBar = { AppBar(title = "I miei dispositivi", navigateUp = back::invoke) }) { padding ->
            LazyColumn(
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                item {
                    SyncIntroduction(configured = state.me != null)
                }
                if (state.me == null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { setup("create") },
                                enabled = manager == null || state.ready && !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (sheet == "create" &&
                                        !state.ready
                                    ) {
                                        "Preparo il collegamento…"
                                    } else {
                                        "Inizia da questo dispositivo"
                                    },
                                )
                            }
                            OutlinedButton(
                                onClick = { setup("pair") },
                                enabled = manager == null || state.ready && !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Collega a un mio dispositivo") }
                            TextButton(onClick = { setup("restore") }, modifier = Modifier.fillMaxWidth()) {
                                Text("Ho una chiave di recupero")
                            }
                            Text(
                                "Sul primo telefono scegli Inizia. Sul secondo scegli Collega: mostrerà un QR da scansionare con il primo.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Sincronizza questo dispositivo", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            when {
                                                !state.syncEnabled -> "Disattivato"
                                                incognito -> "In pausa durante l’incognito"
                                                else -> state.deliveryLabel()
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(checked = state.syncEnabled, onCheckedChange = { manager?.setSync(it) })
                                }
                                if (state.lastReceipt > 0) {
                                    Text(
                                        "Ultimo invio confermato: " +
                                            DateFormat.getDateTimeInstance(
                                                DateFormat.SHORT,
                                                DateFormat.SHORT,
                                            ).format(Date(state.lastReceipt)),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    "Il sync lavora con Nyanime aperta e recupera gli aggiornamenti alla riapertura. Puoi riprendere dalla Home, dalla cronologia o da qui.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (state.syncEnabled && (state.relayIssue != null || state.connected == 0)) {
                                    Text(
                                        "Collegamento in attesa. I progressi restano salvati sul telefono.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = { manager?.retryDeliveries() }) { Text("Riprova ora") }
                                }
                            }
                        }
                    }
                    if (state.recent.isNotEmpty()) {
                        item {
                            Text(
                                "Riprendi da qui",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        items(state.recent, key = { it.ref.key() }) { record ->
                            ResumeCard(record, opening == record.ref.key(), enabled = opening == null) {
                                scope.launch {
                                    opening = record.ref.key()
                                    try {
                                        val ids = manager?.resumeIds(record)
                                        if (ids == null) {
                                            selected = record
                                            sheet = "associate"
                                        } else {
                                            context.startActivity(
                                                if (record.ref.manga) {
                                                    ReaderActivity.newIntent(context, ids.first, ids.second)
                                                } else {
                                                    PlayerActivity.newIntent(context, ids.first, ids.second)
                                                },
                                            )
                                        }
                                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        localError =
                                            "Non riesco ad aprire il contenuto. Verifica che la sua estensione sia installata."
                                    } finally {
                                        opening = null
                                    }
                                }
                            }
                        }
                    }
                    if (state.unresolved.isNotEmpty()) {
                        item {
                            Text("Da ritrovare su questo dispositivo", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Installa la stessa estensione oppure associa manualmente l’edizione corretta.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        items(state.unresolved.take(20), key = { "pending:" + it.ref.key() }) { record ->
                            OutlinedButton(onClick = {
                                selected = record
                                sheet = "associate"
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(listOf(record.title, record.item).filter(String::isNotBlank).joinToString(" · "))
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                sheet = "pair"
                            }, enabled = state.syncEnabled, modifier = Modifier.fillMaxWidth()) {
                                Text("Collega un dispositivo")
                            }
                            OutlinedButton(onClick = { sheet = "export" }, modifier = Modifier.fillMaxWidth()) {
                                Text("Salva la chiave di recupero")
                            }
                            Text(
                                "Download, file Ultra, credenziali e impostazioni hardware restano su questo telefono. La chiave di recupero dà accesso al tuo sync: conservala insieme alla password.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                (state.error ?: localError)?.let { error ->
                    item {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = {
                            manager?.clearError()
                            localError = null
                        }) { Text("Chiudi avviso") }
                    }
                }
                item {
                    Text(
                        "Solo i tuoi dispositivi. Nessun profilo pubblico.",
                        Modifier.padding(bottom = 24.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        manager?.takeIf { state.ready }?.let { current ->
            when (sheet) {
                "pair" -> PairingSheet(current) { sheet = "" }
                "restore" -> RecoverySheet(current, restore = true) { sheet = "" }
                "export" -> RecoverySheet(current, restore = false) { sheet = "" }
                "associate" -> selected?.let { AssociationSheet(it, state, current) { sheet = "" } }
            }
        }
    }
}

@Composable
private fun SyncIntroduction(configured: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(
            Modifier.background(
                Brush.linearGradient(listOf(colors.primaryContainer, colors.surfaceContainer)),
            ).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Outlined.Devices, null, Modifier.size(40.dp), tint = colors.primary)
            Text(
                if (configured) "La tua storia, ovunque." else "Cambia schermo.\nContinua la storia.",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Anime, manga e libreria ti seguono da un dispositivo all’altro. Il punto di ripresa resta al posto giusto.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp))
                Text("Cifrato da dispositivo a dispositivo", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ResumeCard(record: SyncRecord, opening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                if (record.ref.manga) Icons.AutoMirrored.Outlined.MenuBook else Icons.Outlined.PlayArrow,
                null,
                Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    record.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    record.item,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (opening) {
                        "Apro…"
                    } else if (record.ref.manga) {
                        "Pagina ${record.position + 1}"
                    } else {
                        "Riprendi da ${record.position / 60_000}:${(record.position / 1000 % 60).toString().padStart(
                            2,
                            '0',
                        )}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
