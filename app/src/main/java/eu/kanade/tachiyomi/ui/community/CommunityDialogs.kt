@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.community

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalMovies
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.data.community.BlossomImages
import eu.kanade.tachiyomi.data.community.CommunityManager
import eu.kanade.tachiyomi.data.community.CommunityState
import eu.kanade.tachiyomi.data.community.PresenceAccess
import eu.kanade.tachiyomi.data.community.ProfileCode
import eu.kanade.tachiyomi.data.community.PublicTitle
import eu.kanade.tachiyomi.data.community.ShelfStatus
import eu.kanade.tachiyomi.data.community.SocialPost
import eu.kanade.tachiyomi.data.community.SyncRecord
import eu.kanade.tachiyomi.data.community.WallAccess
import eu.kanade.tachiyomi.data.community.hex
import eu.kanade.tachiyomi.data.community.readBounded
import eu.kanade.tachiyomi.data.community.safeImage
import eu.kanade.tachiyomi.data.community.sha256
import eu.kanade.tachiyomi.data.community.validDraft
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import eu.kanade.tachiyomi.ui.watch.WatchTogetherActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CommunityDialog(
    route: String,
    state: CommunityState,
    manager: CommunityManager,
    chat: String,
    close: () -> Unit,
    navigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) manager.setBackground(true)
    }
    when {
        route == "profile" || route.startsWith("profile:") -> ProfileEditor(
            state,
            manager,
            close,
            when (route.substringAfter(':', "")) {
                "favorites" -> 1
                "lists" -> 2
                "wall" -> 3
                else -> 0
            },
        )
        route == "post" || route.startsWith("wall:") -> PostComposer(
            manager,
            state,
            wall = route.removePrefix("wall:").takeIf {
                route.startsWith("wall:")
            }.orEmpty(),
            onClose = close,
        )
        route.startsWith("code:") -> {
            val key = route.substringAfter(':')
            val code = manager.code(key)
            val link by produceState("nyanime://profile/$code", key) { value = manager.profileLink(key) }
            SheetFrame("Il codice di ${state.profile(key).name}", close) {
                QrImage(link)
                SelectionContainer { Text(code, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Questo codice identifica il profilo. Non contiene chiavi segrete.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(
                                Intent.ACTION_SEND,
                            ).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link),
                            "Condividi profilo",
                        ),
                    )
                }, modifier = Modifier.fillMaxWidth()) { Text("Condividi") }
            }
        }
        route == "friend" -> {
            var query by remember { mutableStateOf("") }
            val scan =
                rememberLauncherForActivityResult(ScanContract()) { result ->
                    result.contents?.let {
                        query = it
                        manager.lookup(it)
                    }
                }
            SheetFrame("Trova le tue persone", close) {
                Text(
                    "Usa il codice o il QR per trovare esattamente un amico. La ricerca per nome dipende dai relay e può mostrare più omonimi.",
                )
                OutlinedTextField(query, {
                    query = it.take(2000)
                }, label = { Text("Codice profilo o nome") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { manager.lookup(query) }) { Text("Cerca") }
                    OutlinedButton(onClick = {
                        scan.launch(
                            ScanOptions().setDesiredBarcodeFormats(
                                ScanOptions.QR_CODE,
                            ).setPrompt("Inquadra il codice profilo").setBeepEnabled(false).setOrientationLocked(false),
                        )
                    }) {
                        Icon(Icons.Outlined.QrCodeScanner, null)
                        Text(" Scansiona")
                    }
                }
                val exact = runCatching { ProfileCode.decode(query) }.getOrNull()
                val matches = if (exact !=
                    null
                ) {
                    listOf(state.profile(exact))
                } else {
                    state.profiles.values.filter {
                        it.name.contains(query, true) &&
                            query.length >= 2
                    }
                }
                matches.filter { it.key != state.me?.key }.take(30).forEach { profile ->
                    PersonRow(
                        profile,
                        manager.code(profile.key).take(20) + "…",
                        {},
                    ) {
                        val requested = state.friends.any {
                            it.peer == profile.key &&
                                (it.accepted || it.outgoing.isNotEmpty())
                        }
                        Button(onClick = {
                            manager.requestFriend(profile.key)
                        }, enabled = !requested) { Text(if (requested) "Richiesta registrata" else "Aggiungi") }
                    }
                }
            }
        }
        route == "group" -> {
            var name by remember { mutableStateOf("") }
            var image by remember { mutableStateOf("") }
            var selected by remember { mutableStateOf(setOf<String>()) }
            SheetFrame("Un posto tutto vostro", close) {
                Text(
                    "Un gruppo privato, fino a 10 persone. I nuovi membri vedono soltanto i messaggi inviati dopo il loro ingresso.",
                )
                OutlinedTextField(name, {
                    name = it.take(60)
                }, label = { Text("Nome del gruppo") }, modifier = Modifier.fillMaxWidth())
                PublicImageField("Immagine del gruppo", image, manager) { image = it }
                state.friends.filter { it.accepted && !it.blocked }.forEach { friend ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(friend.peer in selected, { checked ->
                            if (!checked) {
                                selected -= friend.peer
                            } else if (selected.size <
                                9
                            ) {
                                selected += friend.peer
                            }
                        })
                        Avatar(state.profile(friend.peer), 32)
                        Text(state.profile(friend.peer).name, Modifier.padding(10.dp))
                    }
                }
                Button(
                    onClick = {
                        manager.createGroup(name, selected.toList(), image)
                        close()
                    },
                    enabled =
                    name.isNotBlank() && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Crea gruppo · ${selected.size + 1}/10")
                }
            }
        }
        route == "chat-options" -> SheetFrame("La vostra conversazione", close) {
            val room by WatchTogetherManager.get(context).controller.state.collectAsState()
            if (room.active) {
                Button(onClick = {
                    manager.invite(chat, room.invite)
                    close()
                }, modifier = Modifier.fillMaxWidth()) { Text("Invita alla mia stanza") }
            } else {
                Button(onClick = {
                    context.startActivity(Intent(context, WatchTogetherActivity::class.java))
                    close()
                }, modifier = Modifier.fillMaxWidth()) { Text("Crea una stanza Guarda insieme") }
            }
            val group = state.groups.find { it.id == chat }
            if (group != null) {
                Text(group.name, style = MaterialTheme.typography.titleLarge)
                group.members.forEach { member ->
                    PersonRow(
                        state.profile(member),
                        if (member ==
                            group.owner
                        ) {
                            "Proprietario"
                        } else {
                            "Membro"
                        },
                        {},
                    ) {
                        if (group.owner == state.me?.key &&
                            member != group.owner
                        ) {
                            TextButton(onClick = {
                                manager.updateGroup(
                                    group.copy(
                                        members =
                                        group.members - member,
                                    ),
                                )
                            }) { Text("Rimuovi") }
                        }
                    }
                }
                if (group.owner == state.me?.key) {
                    var name by remember(group.name) { mutableStateOf(group.name) }
                    var image by remember(group.image) { mutableStateOf(group.image) }
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Nome del gruppo") })
                    PublicImageField("Immagine del gruppo", image, manager) { image = it }
                    TextButton(onClick = {
                        manager.updateGroup(group.copy(name = name, image = image))
                    }) { Text("Salva gruppo") }
                    state.friends.filter { it.accepted && !it.blocked && it.peer !in group.members }.forEach { friend ->
                        TextButton(onClick = {
                            manager.updateGroup(
                                group.copy(
                                    members =
                                    group.members + friend.peer,
                                ),
                            )
                        }, enabled = group.members.size < 10) { Text("Invita ${state.profile(friend.peer).name}") }
                    }
                }
                TextButton(onClick = {
                    manager.leaveGroup(group.id)
                    close()
                }) {
                    Text(
                        if (group.owner ==
                            state.me?.key
                        ) {
                            "Chiudi gruppo"
                        } else {
                            "Lascia gruppo"
                        },
                    )
                }
            } else {
                TextButton(onClick = {
                    manager.removeFriend(chat)
                    close()
                }) { Text("Rimuovi amicizia") }
            }
        }
        route == "pair" -> PairingSheet(manager, close)
        route == "restore" || route == "recovery" -> RecoverySheet(manager, route == "restore", close)
        route.startsWith("associate:") -> {
            val record = state.unresolved.find { it.ref.key() == route.substringAfter(':') }
            if (record != null) AssociationSheet(record, state, manager, close) else LaunchedEffect(Unit) { close() }
        }
        route == "settings" -> SheetFrame("Il tuo spazio, le tue regole", close) {
            Text("Dispositivi e ripresa", style = MaterialTheme.typography.titleLarge)
            SettingSwitch(
                "Sincronizza la mia libreria",
                "Progressi, preferiti e categorie cifrati. Download e credenziali restano locali.",
                state.syncEnabled,
                manager::setSync,
            )
            SettingSwitch(
                "Ricevi messaggi anche fuori dall’app",
                "Mantiene chat, inviti e sincronizzazione attivi con una notifica Android. Se disattivato, recuperi tutto quando riapri l’app.",
                state.background,
                { enabled ->
                    if (enabled &&
                        android.os.Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                        ) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        manager.setBackground(enabled)
                    }
                },
            )
            OutlinedButton(onClick = {
                navigate("pair")
            }, modifier = Modifier.fillMaxWidth()) { Text("Collega un altro dispositivo") }
            OutlinedButton(onClick = {
                navigate("recovery")
            }, modifier = Modifier.fillMaxWidth()) { Text("Salva una chiave di recupero protetta") }
            Text(
                "I dispositivi collegati condividono la stessa identità. Una chiave già copiata non può essere revocata con una semplice disconnessione.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("Presenza", style = MaterialTheme.typography.titleLarge)
            Text("La cronologia dettagliata non viene pubblicata. La presenza scade automaticamente.")
            PresenceAccess.entries.forEach { access ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.presenceAccess == access, { manager.setPresence(access) })
                    Text(
                        when (access) {
                            PresenceAccess.Private -> "Privato"
                            PresenceAccess.Friends -> "Solo amici"
                            PresenceAccess.Public -> "Pubblico"
                        },
                    )
                }
            }
            if (state.unresolved.isNotEmpty()) {
                Text(
                    "${state.unresolved.size} contenuti richiedono un’associazione: aprili con la stessa estensione e verifica l’edizione prima di riprendere.",
                    color = MaterialTheme.colorScheme.error,
                )
                state.unresolved.take(30).forEach { record ->
                    OutlinedButton(onClick = {
                        navigate("associate:${record.ref.key()}")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(record.title, maxLines = 2)
                            if (record.item.isNotBlank()) {
                                Text(
                                    record.item,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                )
                            }
                            Text("Scegli corrispondenza", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            HorizontalDivider()
            Text("Connessioni Nostr", style = MaterialTheme.typography.titleLarge)
            Text(state.deliveryLabel(), style = MaterialTheme.typography.bodyMedium)
            state.relayIssue?.let { issue ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(issue, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Gli aggiornamenti restano sul dispositivo. Il primo collegamento può includere molti episodi e capitoli della libreria.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = manager::retryDeliveries) { Text("Riprova ora") }
                    }
                }
            }

            var relays by remember { mutableStateOf(manager.relays().joinToString("\n")) }
            OutlinedTextField(relays, {
                relays = it.take(2000)
            }, label = { Text("Un relay wss:// per riga") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                manager.setRelays(relays.lines().map(String::trim).filter(String::isNotEmpty))
            }) { Text("Salva relay") }
            state.friends.filter {
                it.blocked
            }.forEach { blocked ->
                TextButton(onClick = {
                    manager.unblock(blocked.peer)
                }) { Text("Sblocca ${state.profile(blocked.peer).name}") }
            }
            Text(
                "I contenuti pubblici possono essere conservati da altri relay e dispositivi. Nasconderli nell’app non garantisce la rimozione di ogni copia.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SheetFrame(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AdaptiveSheet(
        onDismissRequest = close,
        enableSwipeDismiss = false,
    ) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(.94f).navigationBarsPadding().imePadding(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Chiudi") }
                }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, summary: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked, change)
    }
}

@Composable
internal fun PostComposer(
    manager: CommunityManager,
    state: CommunityState,
    wall: String = "",
    reply: String = "",
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf(state.postDraft?.text.orEmpty()) }
    var image by remember { mutableStateOf(state.postDraft?.image.orEmpty()) }
    var spoiler by remember { mutableStateOf(state.postDraft?.spoiler ?: false) }
    var sticker by remember { mutableStateOf(state.postDraft?.sticker.orEmpty()) }
    var title by remember { mutableStateOf(state.postDraft?.title) }
    var library by remember { mutableStateOf(false) }
    val post = SocialPost(text, image, title, spoiler, sticker)
    AdaptiveSheet(onDismissRequest = onClose, enableSwipeDismiss = false) {
        CommunityEditorFrame(
            title = when {
                library -> "Consiglia una storia"
                reply.isNotEmpty() -> "Rispondi"
                wall.isNotEmpty() -> "Lascia un pensiero"
                else -> "Una storia da condividere"
            },
            detail = if (wall.isNotEmpty()) {
                "Sulla bacheca di ${state.profile(
                    wall,
                ).name} · pubblico"
            } else {
                "Scegli cosa condividere con gli altri"
            },
            close = onClose,
            footer = {
                if (library) {
                    TextButton(onClick = {
                        library = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("Torna al messaggio") }
                } else {
                    Button(
                        onClick = {
                            manager.post(post, wall, reply)
                            onClose()
                        },
                        enabled =
                        post.validDraft() && !state.publishing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (reply.isNotEmpty()) "Pubblica risposta" else "Pubblica")
                    }
                }
            },
        ) {
            if (library) {
                CommunityTitlePicker(state.library.map { it.publicTitle() }, title?.let { setOf(it.id) }.orEmpty(), 1, {
                    library =
                        false
                }, singleChoice = true) {
                    title = it
                    library = false
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    OutlinedTextField(text, {
                        text = it.take(4000)
                    }, placeholder = {
                        Text(
                            if (wall.isNotEmpty()) "Un saluto, un ricordo, una storia da consigliare…" else "Che cosa ti ha colpito?",
                        )
                    }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("heart", "star", "cat", "popcorn").forEach { name ->
                            Surface(
                                onClick = {
                                    sticker = if (sticker ==
                                        name
                                    ) {
                                        ""
                                    } else {
                                        name
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = if (sticker ==
                                    name
                                ) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Box(contentAlignment = Alignment.Center) { NyanimeSticker(name) }
                            }
                        }
                    }
                    title?.let {
                        TitleTile(it)
                        TextButton(onClick = { title = null }) { Text("Rimuovi il titolo") }
                    }
                    OutlinedButton(onClick = { library = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (title ==
                                null
                            ) {
                                "Consiglia un titolo"
                            } else {
                                "Cambia titolo"
                            },
                        )
                    }
                    PublicImageField("Immagine", image, manager, stage = true) { image = it }
                    SettingSwitch(
                        "Proteggi dagli spoiler",
                        "Il contenuto appare solo quando chi legge sceglie di mostrarlo.",
                        spoiler,
                    ) {
                        spoiler =
                            it
                    }
                    Text(
                        "Verranno pubblicati soltanto il messaggio e gli allegati che hai scelto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProfileEditor(
    state: CommunityState,
    manager: CommunityManager,
    close: () -> Unit,
    initialSection: Int = 0,
) {
    var profile by remember { mutableStateOf(state.profileDraft ?: requireNotNull(state.me)) }
    LaunchedEffect(profile) {
        kotlinx.coroutines.delay(350)
        manager.saveProfileDraft(profile)
    }
    val dismiss = {
        manager.saveProfileDraft(profile)
        close()
    }
    AdaptiveSheet(onDismissRequest = dismiss, enableSwipeDismiss = false) {
        ProfileStudio(
            state,
            profile,
            manager,
            initialSection,
            change = { profile = it },
            publish = {
                manager.publishProfile(profile)
                close()
            },
            close = dismiss,
            imageEditor = { label, image, change ->
                PublicImageField(label, image, manager, stage = true, change = change)
            },
        )
    }
}
internal fun SyncRecord.publicTitle() = PublicTitle(
    sha256((title.lowercase(java.util.Locale.ROOT) + ref.manga).toByteArray()).hex(),
    title,
    ref.manga,
    artwork.takeIf(::safeImage).orEmpty(),
)

@Composable
private fun PublicImageField(
    label: String,
    value: String,
    manager: CommunityManager,
    stage: Boolean = false,
    change: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var local by remember { mutableStateOf("") }
    var link by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val bytes = BlossomImages.prepare(context, uri)
                    local = manager.stageImage(bytes)
                    if (stage) change(local) else pending = bytes
                    error = ""
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    error = "Non riesco ad aprire questa immagine. Prova un’altra foto."
                } finally {
                    busy = false
                }
            }
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val image = local.ifEmpty { value }
            if (image.isNotEmpty()) {
                CommunityImage(
                    image,
                    label,
                    Modifier.fillMaxWidth().height(
                        if (label ==
                            "Foto profilo"
                        ) {
                            108.dp
                        } else {
                            136.dp
                        },
                    ),
                )
            }
            OutlinedButton(onClick = {
                picker.launch("image/*")
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AddPhotoAlternate, null)
                Text(
                    if (busy) {
                        " Preparo la foto…"
                    } else if (image.isEmpty()) {
                        " Scegli una foto"
                    } else {
                        " Cambia foto"
                    },
                )
            }
            if (pending != null) {
                Text(
                    "La foto verrà caricata su un servizio pubblico di immagini. I metadati della foto vengono rimossi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            change(manager.uploadPublicArtwork(requireNotNull(pending)))
                            pending = null
                            local = ""
                            error =
                                ""
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            error =
                                failure.message ?: "Caricamento non riuscito. La foto è ancora qui: riprova."
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Carico la foto…" else "Usa questa foto")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { link = !link }) { Text("Usa un link") }
                if (image.isNotEmpty()) {
                    TextButton(onClick = {
                        change("")
                        local = ""
                        pending = null
                    }) { Text("Rimuovi") }
                }
            }
            if (link) {
                OutlinedTextField(
                    value.takeUnless { it.startsWith("nyanime-image:") }.orEmpty(),
                    {
                        change(it.take(2048))
                        local =
                            ""
                        pending = null
                    },
                    label = {
                        Text("Indirizzo https dell’immagine")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                    ),
                )
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun RecoverySheet(manager: CommunityManager, restore: Boolean, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var encoded by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri !=
                null
            ) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                                it.write(encoded.toByteArray())
                            }
                        }
                        encoded = ""
                        close()
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        error = "Il file non è stato salvato. Scegli una destinazione e riprova."
                    }
                }
            }
        }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri !=
            null
        ) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readBounded(200).decodeToString()
                        }.orEmpty()
                    }
                }.onSuccess {
                    encoded =
                        it
                }.onFailure { error = "Non riesco a leggere il file." }
            }
        }
    }
    SheetFrame(if (restore) "Recupera i tuoi dispositivi" else "La tua chiave di recupero", close) {
        Text(
            "Conserva il file protetto e la password in un posto sicuro. Servono per recuperare i dati cifrati quando cambi telefono.",
        )
        if (restore) {
            OutlinedButton(onClick = { load.launch("text/*") }) { Text("Apri file di recupero") }
            OutlinedTextField(encoded, {
                encoded =
                    it.take(200)
            }, label = { Text("Oppure incolla il codice protetto") }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(password, {
            password = it.take(256)
        }, label = {
            Text("Password di almeno 10 caratteri")
        }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = password.length >= 10 && !busy && (!restore || encoded.isNotBlank()), modifier = Modifier.fillMaxWidth(), onClick = {
            if (restore) {
                manager.restoreRecovery(encoded, password.toCharArray())
                password = ""
                close()
            } else {
                scope.launch {
                    busy = true
                    try {
                        encoded = manager.exportRecovery(password.toCharArray())
                        password =
                            ""
                        save.launch(
                            if (manager.personalOnly) "Nyanime-sync-protetto.txt" else "Nyanime-profilo-protetto.txt",
                        )
                    } catch (_: Exception) {
                        error =
                            "Esportazione non riuscita. Riprova."
                    } finally {
                        busy = false
                    }
                }
            }
        }) {
            Text(
                if (busy) {
                    "Proteggo la chiave…"
                } else if (restore) {
                    "Recupera"
                } else {
                    "Esporta file protetto"
                },
            )
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun PairingSheet(manager: CommunityManager, close: () -> Unit) {
    val context = LocalContext.current
    val pairing = remember { manager.linkedPairing() }
    val state by pairing.state.collectAsState()
    var code by remember { mutableStateOf("") }
    DisposableEffect(pairing) { onDispose { pairing.close() } }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { pairing.join(it) } }
    SheetFrame("I tuoi dispositivi, insieme", close) {
        if (state.finished) {
            EmptyStory(
                Icons.Outlined.CheckCircle,
                "Collegamento completato",
                "Recupero libreria e progressi. Li ritroverai nella Home e nella cronologia.",
            )
            Button(onClick = close) { Text("Continua") }
        } else if (state.comparison.isNotEmpty()) {
            Text("Controlla che entrambi i dispositivi mostrino lo stesso numero.")
            Text(
                state.comparison.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = pairing::confirm, enabled = !state.confirmed, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.confirmed) "In attesa dell’altro dispositivo…" else "Il numero corrisponde")
            }
        } else if (state.code.isNotEmpty()) {
            Text(
                if (manager.personalOnly) {
                    "Sul telefono già configurato apri Impostazioni → I miei dispositivi → Collega un dispositivo e scansiona questo QR."
                } else {
                    "Sul telefono già collegato apri Community → Privacy e dispositivi → Collega un altro dispositivo e scansiona questo QR."
                },
            )
            QrImage(state.code)
            if (manager.personalOnly) {
                TextButton(onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(
                            android.content.ClipData.newPlainText("Codice temporaneo dispositivo", state.code),
                        )
                }) { Text("Copia codice temporaneo") }
            } else {
                SelectionContainer { Text(state.code, style = MaterialTheme.typography.labelSmall) }
            }
            Text("Monouso · valido per 3 minuti", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                if (manager.personalOnly) {
                    "Sul nuovo dispositivo apri Impostazioni → I miei dispositivi → Collega a un mio dispositivo. Qui scansiona il suo QR temporaneo."
                } else {
                    "Sul nuovo dispositivo apri Community → Collega dispositivo. Qui scansiona il suo QR temporaneo."
                },
            )
            Button(onClick = {
                scan.launch(
                    ScanOptions().setDesiredBarcodeFormats(
                        ScanOptions.QR_CODE,
                    ).setPrompt(
                        "Inquadra il QR del nuovo dispositivo",
                    ).setBeepEnabled(false).setOrientationLocked(false),
                )
            }) { Text("Scansiona QR") }
            OutlinedTextField(code, {
                code = it.take(2000)
            }, label = { Text("Oppure incolla il codice dispositivo") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { pairing.join(code) }, enabled = code.isNotBlank()) { Text("Collega") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun QrImage(payload: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, payload) {
        value =
            withContext(Dispatchers.Default) { BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 640, 640) }
    }
    Box(Modifier.fillMaxWidth().height(260.dp).background(Color.White), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "Codice QR", Modifier.size(252.dp)) }
    }
}
