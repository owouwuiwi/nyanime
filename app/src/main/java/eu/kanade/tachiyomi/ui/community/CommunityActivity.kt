@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.community

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.LocalMovies
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.community.CommunityInteractions
import eu.kanade.tachiyomi.data.community.CommunityItem
import eu.kanade.tachiyomi.data.community.CommunityManager
import eu.kanade.tachiyomi.data.community.CommunityProfile
import eu.kanade.tachiyomi.data.community.CommunityState
import eu.kanade.tachiyomi.data.community.ProfileCode
import eu.kanade.tachiyomi.data.community.PublicTitle
import eu.kanade.tachiyomi.data.watch.WatchInvite
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.watch.WatchTogetherActivity

class CommunityActivity : BaseActivity() {
    private var incoming by mutableStateOf("")
    private var conversation by mutableStateOf("")
    private fun accept(intent: Intent) {
        incoming = intent.dataString.orEmpty().takeIf { runCatching { ProfileCode.decode(it) }.isSuccess }.orEmpty()
        conversation =
            intent.getStringExtra("conversation").orEmpty().takeIf {
                it.length in 32..64 &&
                    it.all { char -> char in "0123456789abcdef" }
            }.orEmpty()
        intent.data = null
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!BuildConfig.COMMUNITY_ENABLED) {
            finish()
            return
        }
        accept(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.COMMUNITY_ENABLED) {
            finish()
            return
        }
        registerSecureActivity(this)
        enableEdgeToEdge()
        accept(intent)
        setContent { TachiyomiTheme { CommunityScreen(CommunityManager.get(this), incoming, conversation, ::finish) } }
    }
}

@Composable
fun CommunityAvatarButton() {
    if (!BuildConfig.COMMUNITY_ENABLED) return
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        IconButton(onClick = {}) { Icon(Icons.Outlined.AccountCircle, "Community e profilo") }
        return
    }
    val context = LocalContext.current
    val manager = remember { CommunityManager.get(context) }
    val state by manager.state.collectAsState()
    IconButton(onClick = { context.startActivity(Intent(context, CommunityActivity::class.java)) }) {
        if (state.me != null) Avatar(state.me!!, 30) else Icon(Icons.Outlined.AccountCircle, "Community e profilo")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityScreen(manager: CommunityManager, incoming: String, incomingChat: String, close: () -> Unit) {
    val state by manager.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var profile by rememberSaveable { mutableStateOf("") }
    var chat by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(incomingChat) { if (incomingChat.isNotEmpty()) chat = incomingChat }
    var dialog by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<CommunityItem?>(null) }
    val motion = modernMotionEnabled()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(incoming) {
        if (incoming.isNotEmpty()) {
            profile = ProfileCode.decode(incoming)
            manager.lookup(incoming)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            manager.clearError()
        }
    }
    val back = {
        if (chat.isNotEmpty()) {
            chat = ""
        } else if (profile.isNotEmpty()) {
            profile = ""
        } else {
            close()
        }
    }
    BackHandler(chat.isNotEmpty() || profile.isNotEmpty()) { back() }
    val titles = listOf("Per te", "Amici", "Chat", "Profilo")
    val icons =
        listOf(
            Icons.Outlined.AutoAwesome,
            Icons.Outlined.PeopleOutline,
            Icons.Outlined.ChatBubbleOutline,
            Icons.Outlined.AccountCircle,
        )
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (chat.isNotEmpty()) {
                                state.groups.find { it.id == chat }?.name
                                    ?: state.profile(chat).name
                            } else {
                                "Community"
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.me !=
                            null
                        ) {
                            Text(
                                state.deliveryLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Indietro") }
                },
                actions = {
                    if (state.me !=
                        null
                    ) {
                        IconButton(onClick = {
                            dialog = "settings"
                        }) { Icon(Icons.Outlined.Tune, "Privacy e dispositivi") }
                    }
                },
            )
        },
        bottomBar = {
            if (state.me != null && chat.isEmpty() && profile.isEmpty()) {
                NavigationBar {
                    titles.forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = {
                            tab = index
                        }, icon = { Icon(icons[index], null) }, label = { Text(label, maxLines = 1) })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.me != null && chat.isEmpty() && tab != 3) {
                ExtendedFloatingActionButton(
                    onClick = {
                        dialog = if (tab == 1) {
                            "friend"
                        } else if (tab == 2) {
                            "group"
                        } else {
                            "post"
                        }
                    },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = {
                        Text(
                            if (tab ==
                                1
                            ) {
                                "Aggiungi amico"
                            } else if (tab == 2) {
                                "Nuovo gruppo"
                            } else {
                                "Condividi"
                            },
                        )
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(
                targetState = when {
                    !state.ready -> "loading"
                    state.me == null -> "welcome"
                    chat.isNotEmpty() -> "chat:$chat"
                    profile.isNotEmpty() -> "profile:$profile"
                    else -> "tab:$tab"
                },
                transitionSpec = {
                    ModernMotion.transform(motion)
                },
                label = "community-page",
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
            ) { route ->
                when {
                    route == "loading" -> EmptyStory(
                        Icons.Outlined.AutoAwesome,
                        "Il tuo spazio, tra un attimo",
                        "Sto aprendo i contenuti salvati sul dispositivo.",
                    )
                    route == "welcome" -> CommunityWelcome(manager) { dialog = it }
                    route.startsWith("chat:") -> Conversation(state, route.substringAfter(':'), manager) {
                        dialog =
                            "chat-options"
                    }
                    route.startsWith("profile:") || route == "tab:3" -> {
                        val owner = if (route == "tab:3") state.me?.key.orEmpty() else route.substringAfter(':')
                        ProfilePage(
                            state, state.profile(owner), manager,
                            onEdit = { dialog = "profile" }, onCode = { dialog = "code:$owner" }, onChat = {
                                chat =
                                    owner
                            },
                            onEditFavorites = { dialog = "profile:favorites" },
                            onEditLists = { dialog = "profile:lists" },
                            onEditWall = { dialog = "profile:wall" },
                            onPost = { dialog = "wall:$owner" }, onReply = { reply = it }, onProfile = {
                                profile = it
                                manager.openProfile(it)
                            },
                            onWatch = {
                                chat = owner
                                if (state.presence[owner]?.activity?.manga == false) {
                                    manager.requestWatch(owner)
                                } else {
                                    dialog = "chat-options"
                                }
                            },
                        )
                    }
                    route == "tab:0" -> FeedPage(state, manager, onProfile = {
                        profile = it
                        manager.openProfile(it)
                    }, onReply = {
                        reply =
                            it
                    })
                    route == "tab:1" -> FriendsPage(state, manager, onProfile = {
                        profile = it
                        manager.openProfile(it)
                    }, onChat = {
                        chat =
                            it
                    }, onSettings = { dialog = "settings" })
                    else -> ChatsPage(state, manager) { chat = it }
                }
            }
        }
    }
    if (dialog.isNotEmpty()) CommunityDialog(dialog, state, manager, chat, { dialog = "" }, { dialog = it })
    reply?.let { item -> PostComposer(manager, state, wall = item.wall, reply = item.id, onClose = { reply = null }) }
}

@Composable
private fun CommunityWelcome(manager: CommunityManager, open: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val state by manager.state.collectAsState()
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Box(
                Modifier.fillMaxWidth().height(
                    180.dp,
                ).clip(
                    RoundedCornerShape(32.dp),
                ).background(Brush.linearGradient(listOf(Color(0xFF4F1D63), Color(0xFF151325)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(76.dp), Color(0xFFFFC5E7))
            }
        }
        item {
            Text(
                "Le storie sono più belle\nquando le condividi.",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Text(
                "Uno spazio per i tuoi preferiti e i tuoi amici. I progressi viaggiano cifrati tra i tuoi dispositivi; scegli tu cosa mostrare sul profilo.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(name, {
                name = it.take(40)
            }, label = { Text("Come ti chiami?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (name.isNotBlank()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Avatar(CommunityProfile(key = "", name = name.trim()))
                    Column {
                        Text(name.trim(), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Il nome e questo avatar saranno pubblici.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = {
                    manager.create(name)
                },
                enabled = name.isNotBlank() && !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(
                    min = 52.dp,
                ),
            ) {
                Text(if (state.loading) "Creo il tuo spazio…" else "Crea il mio profilo")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { open("pair") }) { Text("Collega dispositivo") }
                TextButton(onClick = { open("restore") }) { Text("Recupera profilo") }
            }
        }
        item {
            Text(
                "Nessuna email. Nessuna cronologia pubblicata automaticamente. Puoi continuare a usare Nyanime anche senza un profilo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun Avatar(profile: CommunityProfile, size: Int = 48) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(profile.accent).copy(alpha = .2f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile.name.take(1).uppercase(),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        if (profile.avatar.isNotBlank()) {
            CommunityImage(
                profile.avatar,
                "Avatar di ${profile.name}",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
internal fun EmptyStory(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, null, Modifier.padding(20.dp).size(32.dp), MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeedPage(
    state: CommunityState,
    manager: CommunityManager,
    onProfile: (String) -> Unit,
    onReply: (CommunityItem) -> Unit,
) {
    var explore by rememberSaveable { mutableStateOf(false) }
    val posts = state.posts.filter {
        it.wall.isEmpty() &&
            it.reply.isEmpty() &&
            (explore || state.isFriend(it.author) || it.author == state.me?.key)
    }.sortedByDescending { it.at }
    LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "Una buona storia\nmerita compagnia.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!explore, { explore = false }, label = { Text("Amici") })
                    FilterChip(explore, {
                        explore =
                            true
                    }, label = { Text("Esplora") })
                }
            }
        }
        if (posts.isEmpty()) {
            item {
                EmptyStory(
                    Icons.Outlined.Forum,
                    if (explore) "Le prossime scoperte iniziano qui" else "Fai spazio ai tuoi amici",
                    "Consigli, pensieri e nuovi titoli. Condividi il primo oppure aggiungi un amico con il suo codice.",
                )
            }
        }
        items(posts, key = { it.id }) { PostCard(it, state, manager, onProfile, onReply) }
        item {
            TextButton(onClick = manager::loadOlder, modifier = Modifier.fillMaxWidth()) {
                Text("Cerca altri contenuti")
            }
        }
    }
}

@Composable
internal fun PostCard(
    item: CommunityItem,
    state: CommunityState,
    manager: CommunityInteractions,
    onProfile: (String) -> Unit,
    onReply: (CommunityItem) -> Unit,
) {
    var revealed by rememberSaveable(item.id) { mutableStateOf(!item.post.spoiler) }
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable {
                        onProfile(item.author)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(state.profile(item.author), 40)
                    Column {
                        Text(state.profile(item.author).name, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (item.pinned) {
                                "In evidenza sulla bacheca"
                            } else {
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT,
                                    java.text.DateFormat.SHORT,
                                ).format(java.util.Date(item.at * 1000))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "Opzioni messaggio") }
                    DropdownMenu(menu, { menu = false }) {
                        if (item.author == state.me?.key || item.wall == state.me?.key) {
                            DropdownMenuItem(text = { Text("Nascondi dalla bacheca") }, onClick = {
                                manager.moderate(item)
                                menu =
                                    false
                            })
                            DropdownMenuItem(text = {
                                Text(if (item.pinned) "Non fissare più" else "Fissa in alto")
                            }, onClick = {
                                manager.moderate(item, true)
                                menu =
                                    false
                            })
                        }
                        if (item.author !=
                            state.me?.key
                        ) {
                            DropdownMenuItem(text = { Text("Blocca questo profilo") }, onClick = {
                                manager.removeFriend(item.author, true)
                                menu =
                                    false
                            })
                        }
                    }
                }
            }
            if (!revealed) {
                OutlinedButton(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.VisibilityOff, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Contiene spoiler · mostra")
                }
            } else {
                if (item.post.sticker.isNotEmpty()) Sticker(item.post.sticker)
                if (item.post.text.isNotEmpty()) Text(item.post.text, style = MaterialTheme.typography.bodyLarge)
                if (item.post.image.isNotEmpty()) {
                    CommunityImage(
                        item.post.image,
                        "Immagine condivisa",
                        Modifier.fillMaxWidth().height(
                            240.dp,
                        ).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
                        contentScale = ContentScale.Crop,
                    )
                }
                item.post.title?.let { TitleTile(it, Modifier.fillMaxWidth()) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { manager.react(item) }) {
                    Icon(
                        if (state.me?.key in
                            item.likes
                        ) {
                            Icons.Outlined.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        "Mi piace",
                        tint = if (state.me?.key in
                            item.likes
                        ) {
                            Color(0xFFFA749F)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text("  ${item.likes.size}")
                }
                TextButton(onClick = { onReply(item) }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null)
                    Text("  Rispondi")
                }
            }
            state.posts.filter { it.reply == item.id }.sortedBy { it.at }.takeLast(5).forEach { answer ->
                Column(
                    Modifier.fillMaxWidth().clip(
                        RoundedCornerShape(14.dp),
                    ).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
                ) {
                    Text(
                        state.profile(answer.author).name,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable {
                            onProfile(answer.author)
                        },
                    )
                    if (!answer.post.spoiler) {
                        Text(
                            answer.post.text,
                        )
                    } else {
                        Text("Risposta con spoiler", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Sticker(name: String) {
    NyanimeSticker(name)
}

@Composable
internal fun TitleTile(title: PublicTitle, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CommunityImage(
            title.artwork,
            null,
            Modifier.width(
                54.dp,
            ).height(76.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Text(title.title, maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                if (title.manga) "Manga" else "Video",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FriendsPage(
    state: CommunityState,
    manager: CommunityManager,
    onProfile: (String) -> Unit,
    onChat: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val requests = state.friends.filter { it.incoming.isNotEmpty() && !it.blocked && !it.accepted }
    val friends = state.friends.filter { !it.blocked && (it.accepted || it.outgoing.isNotEmpty()) }
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Le tue persone", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { CommunityNotificationCard(state.background, onSettings) }
        item {
            Surface(
                onClick = onSettings,
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cosa stai guardando?", fontWeight = FontWeight.SemiBold)
                    Text(
                        when (state.presenceAccess) {
                            eu.kanade.tachiyomi.data.community.PresenceAccess.Private -> "Attività nascosta · scegli tu quando condividerla"
                            eu.kanade.tachiyomi.data.community.PresenceAccess.Friends -> "La tua attività è visibile solo agli amici"
                            eu.kanade.tachiyomi.data.community.PresenceAccess.Public -> "La tua attività è pubblica"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (requests.isNotEmpty()) item { Text("Richieste di amicizia", style = MaterialTheme.typography.titleMedium) }
        items(requests, key = { "request:${it.peer}" }) { friend ->
            PersonRow(state.profile(friend.peer), "Vorrebbe aggiungerti agli amici", { onProfile(friend.peer) }) {
                TextButton(onClick = { manager.removeFriend(friend.peer) }) { Text("Rifiuta") }
                Button(onClick = { manager.acceptFriend(friend.peer) }) { Text("Accetta") }
            }
        }
        if (friends.isEmpty()) {
            item {
                EmptyStory(
                    Icons.Outlined.PeopleOutline,
                    "Una visione, mille conversazioni",
                    "Aggiungi un amico con QR o codice. La lista degli amici resta privata.",
                )
            }
        }
        items(friends, key = { it.peer }) { friend ->
            if (friend.accepted) {
                FriendActivityCard(
                    state.profile(friend.peer),
                    state.presence[friend.peer],
                    state.watchRequests.values.any {
                        it.host == friend.peer &&
                            it.requester == state.me?.key &&
                            it.pending(System.currentTimeMillis())
                    },
                    onProfile = { onProfile(friend.peer) },
                    onChat = { onChat(friend.peer) },
                    onWatch = {
                        manager.requestWatch(friend.peer)
                        onChat(friend.peer)
                    },
                )
            } else {
                PersonRow(
                    state.profile(friend.peer),
                    if (friend.accepted) {
                        state.presence[friend.peer]?.text
                            ?: "Amico"
                    } else {
                        "Richiesta inviata"
                    },
                    { onProfile(friend.peer) },
                ) {
                    if (friend.accepted) {
                        IconButton(onClick = {
                            onChat(friend.peer)
                        }) { Icon(Icons.Outlined.ChatBubbleOutline, "Scrivi") }
                    } else {
                        TextButton(onClick = { manager.removeFriend(friend.peer) }) { Text("Annulla") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PersonRow(
    profile: CommunityProfile,
    detail: String,
    click: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = click),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(profile)
                Column(Modifier.weight(1f)) {
                    Text(profile.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        detail,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
        }
    }
}

@Composable
private fun ChatsPage(state: CommunityState, manager: CommunityManager, open: (String) -> Unit) {
    val ids = (
        state.groups.map {
            it.id
        } +
            state.friends.filter { it.accepted && !it.blocked }.map { it.peer }
        ).distinct().sortedByDescending { id ->
        state.chats.filter {
            it.conversation ==
                id
        }.maxOfOrNull { it.at }
            ?: 0
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Conversazioni", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Text("Messaggi cifrati, per le persone che scegli.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.groupInvites, key = { "invite:" + it.id }) { group ->
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Un invito per te",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${state.profile(group.owner).name} ti invita in un gruppo di ${group.members.size} persone.")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { manager.acceptGroup(group.id) }) { Text("Entra nel gruppo") }
                        TextButton(onClick = { manager.declineGroup(group.id) }) { Text("Rifiuta") }
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            item {
                EmptyStory(
                    Icons.Outlined.MarkChatUnread,
                    "Il prossimo «lo guardiamo?»",
                    "Le chat con gli amici e i tuoi gruppi appariranno qui.",
                )
            }
        }
        items(ids, key = { it }) { id ->
            val group = state.groups.find { it.id == id }
            val last = state.chats.filter { it.conversation == id }.maxByOrNull { it.at }
            PersonRow(
                if (group ==
                    null
                ) {
                    state.profile(id)
                } else {
                    CommunityProfile(id, group.name, avatar = group.image)
                },
                last?.text ?: if (group ==
                    null
                ) {
                    "Inizia una conversazione"
                } else {
                    "${group.members.size} persone"
                },
                { open(id) },
            )
        }
    }
}

@Composable
private fun Conversation(state: CommunityState, id: String, manager: CommunityManager, options: () -> Unit) {
    var text by rememberSaveable(id) { mutableStateOf("") }
    val context = LocalContext.current
    val messages = state.chats.filter { it.conversation == id }.sortedByDescending { it.at }
    Column(Modifier.fillMaxSize().imePadding()) {
        state.groupInvites.firstOrNull { it.id == id }?.let { group ->
            PersonRow(
                eu.kanade.tachiyomi.data.community.CommunityProfile(group.id, group.name, avatar = group.image),
                "${state.profile(group.owner).name} ti invita in questo gruppo",
                {},
            ) {
                TextButton(onClick = { manager.declineGroup(id) }) { Text("Non ora") }
                Button(onClick = { manager.acceptGroup(id) }) { Text("Entra nel gruppo") }
            }
        }
        val incoming = state.friends.firstOrNull {
            it.peer == id &&
                !it.accepted &&
                !it.blocked &&
                it.incoming.isNotEmpty()
        }
        if (incoming != null) {
            PersonRow(state.profile(id), "Vorrebbe aggiungerti agli amici", {}) {
                TextButton(onClick = { manager.removeFriend(id) }) { Text("Non ora") }
                Button(onClick = { manager.acceptFriend(id) }) { Text("Accetta amicizia") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { manager.mute(id) }) {
                Icon(
                    if (id in
                        state.muted
                    ) {
                        Icons.Outlined.NotificationsOff
                    } else {
                        Icons.Outlined.NotificationsNone
                    },
                    null,
                )
                Text(
                    if (id in
                        state.muted
                    ) {
                        " Silenziata"
                    } else {
                        " Notifiche"
                    },
                )
            }
            TextButton(onClick = options) { Text("Opzioni") }
        }
        LazyColumn(
            Modifier.weight(1f),
            reverseLayout = true,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                val watch = state.watchRequests[message.watchRequest]
                if (watch != null) {
                    SocialWatchCard(
                        watch,
                        state.me?.key.orEmpty(),
                        state.profile(id).name,
                        respond = { accept ->
                            (context as? android.app.Activity)?.let { manager.respondWatch(watch.id, accept, it) }
                        },
                        enter = { (context as? android.app.Activity)?.let { manager.enterWatch(watch.id, it) } },
                    )
                } else {
                    val mine = message.author == state.me?.key
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                    ) {
                        Surface(
                            Modifier.widthIn(max = 330.dp),
                            shape = RoundedCornerShape(
                                22.dp,
                                22.dp,
                                if (mine) 5.dp else 22.dp,
                                if (mine) 22.dp else 5.dp,
                            ),
                            color = if (mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!mine &&
                                    state.groups.any { it.id == id }
                                ) {
                                    Text(
                                        state.profile(message.author).name,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text(message.text)
                                if (message.invite.isNotEmpty()) {
                                    Button(
                                        onClick = {
                                            val invite = runCatching {
                                                require(
                                                    message.inviteExpires == 0L ||
                                                        message.inviteExpires > System.currentTimeMillis(),
                                                )
                                                WatchInvite.parse(message.invite, System.currentTimeMillis())
                                            }.getOrNull()
                                            if (invite == null) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "L’invito è scaduto. Chiedine uno nuovo.",
                                                    android.widget.Toast.LENGTH_LONG,
                                                ).show()
                                            } else {
                                                context.startActivity(
                                                    Intent(
                                                        context,
                                                        WatchTogetherActivity::class.java,
                                                    ).setAction(
                                                        Intent.ACTION_VIEW,
                                                    ).setData(
                                                        invite.link().toUri(),
                                                    ),
                                                )
                                            }
                                        },
                                        enabled = runCatching {
                                            require(
                                                message.inviteExpires == 0L ||
                                                    message.inviteExpires > System.currentTimeMillis(),
                                            )
                                            WatchInvite.parse(message.invite, System.currentTimeMillis())
                                        }.isSuccess,
                                    ) { Text("Entriamo in stanza") }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(text, {
                text = it.take(4000)
            }, placeholder = {
                Text("Scrivi qualcosa…")
            }, modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(26.dp))
            FilledIconButton(
                onClick = {
                    manager.sendChat(id, text)
                    text = ""
                },
                enabled = text.isNotBlank() &&
                    (
                        state.isFriend(id) ||
                            state.groups.any {
                                it.id == id
                            }
                        ),
            ) { Icon(Icons.AutoMirrored.Outlined.Send, "Invia messaggio cifrato") }
        }
    }
}

internal fun openCommunity(context: Context) {
    if (!BuildConfig.COMMUNITY_ENABLED) return
    context.startActivity(Intent(context, CommunityActivity::class.java))
}
