@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.community.CommunityInteractions
import eu.kanade.tachiyomi.data.community.CommunityItem
import eu.kanade.tachiyomi.data.community.CommunityProfile
import eu.kanade.tachiyomi.data.community.CommunityState
import eu.kanade.tachiyomi.data.community.PublicTitle
import eu.kanade.tachiyomi.data.community.ShelfStatus
import eu.kanade.tachiyomi.data.community.WallAccess

internal val shelfNames = mapOf(
    ShelfStatus.Watching to "In corso",
    ShelfStatus.Planned to "Da vedere",
    ShelfStatus.Completed to "Completati",
    ShelfStatus.Paused to "In pausa",
    ShelfStatus.Dropped to "Abbandonati",
)

@Composable
internal fun ProfilePage(
    state: CommunityState,
    profile: CommunityProfile,
    manager: CommunityInteractions,
    onEdit: () -> Unit,
    onCode: () -> Unit,
    onChat: () -> Unit,
    onPost: () -> Unit,
    onReply: (CommunityItem) -> Unit,
    onProfile: (String) -> Unit,
    preview: Boolean = false,
    onWatch: (() -> Unit)? = null,
    onEditFavorites: () -> Unit = onEdit,
    onEditLists: () -> Unit = onEdit,
    onEditWall: () -> Unit = onEdit,
) {
    var section by rememberSaveable(profile.key) { mutableIntStateOf(0) }
    var status by rememberSaveable(profile.key) { mutableStateOf(ShelfStatus.Watching) }
    var medium by rememberSaveable(profile.key) { mutableIntStateOf(0) }
    val mine = profile.key == state.me?.key
    val titles = profile.shelves.filter { it.status == status && (medium == 0 || it.manga == (medium == 2)) }
    val accent = Color(profile.accent)
    LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Box(
                Modifier.fillMaxWidth().height(
                    (238 * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)).dp,
                ).background(
                    Brush.linearGradient(listOf(accent.copy(alpha = .6f), MaterialTheme.colorScheme.background)),
                ),
            ) {
                if (profile.banner.isNotEmpty()) {
                    CommunityImage(
                        profile.banner,
                        "Copertina del profilo",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)),
                    ),
                )
                Row(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Avatar(profile, 82)
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.presence[profile.key]?.text ?: "Il mio angolo di storie",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (profile.bio.isNotBlank()) Text(profile.bio, style = MaterialTheme.typography.bodyLarge)
                if (!preview) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            mine -> Button(onClick = onEdit, modifier = Modifier.weight(1f, fill = false)) {
                                Icon(Icons.Outlined.Edit, null)
                                Text(" Personalizza")
                            }
                            state.isFriend(
                                profile.key,
                            ) -> Button(onClick = onChat, modifier = Modifier.weight(1f, fill = false)) {
                                Icon(Icons.Outlined.ChatBubbleOutline, null)
                                Text(" Messaggio")
                            }
                            else -> Button(
                                modifier = Modifier.weight(1f, fill = false),
                                onClick = { manager.requestFriend(profile.key) },
                                enabled = state.friends.none {
                                    it.peer ==
                                        profile.key &&
                                        (it.outgoing.isNotEmpty() || it.blocked)
                                },
                            ) {
                                Text(
                                    if (state.friends.any {
                                            it.peer ==
                                                profile.key &&
                                                it.outgoing.isNotEmpty()
                                        }
                                    ) {
                                        "Richiesta inviata"
                                    } else {
                                        "Aggiungi amico"
                                    },
                                )
                            }
                        }
                        OutlinedIconButton(onClick = onCode, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.QrCode2, "Codice profilo")
                        }
                    }
                    if (!mine && state.isFriend(profile.key) && onWatch != null) {
                        if (state.presence[profile.key]?.activity != null) {
                            FriendActivityCard(
                                profile,
                                state.presence[profile.key],
                                state.watchRequests.values.any {
                                    it.host == profile.key &&
                                        it.requester == state.me?.key &&
                                        it.pending(System.currentTimeMillis())
                                },
                                onProfile = {},
                                onChat = onChat,
                                onWatch = onWatch,
                            )
                        } else {
                            OutlinedButton(onClick = onWatch) {
                                Icon(Icons.Outlined.AutoAwesome, null)
                                Text(" Guarda insieme")
                            }
                        }
                    }
                }
            }
        }
        if (profile.favorites.isNotEmpty() || mine && !preview) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Le mie tre scelte",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (mine &&
                        !preview
                    ) {
                        IconButton(onClick = onEditFavorites) {
                            Icon(Icons.Outlined.Edit, "Modifica i tre preferiti")
                        }
                    }
                }
            }
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    if (profile.favorites.isEmpty()) {
                        items(3) { index ->
                            Surface(
                                onClick = onEditFavorites,
                                modifier = Modifier.width(120.dp).height(168.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = accent.copy(alpha = .12f),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        "0${index + 1}",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = accent,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Icon(Icons.Outlined.Add, "Scegli un preferito")
                                    Text("Una storia che ami", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    items(profile.favorites, key = { it.id }) { title ->
                        Column(Modifier.width(146.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.fillMaxWidth().height(
                                    204.dp,
                                ).clip(
                                    RoundedCornerShape(18.dp),
                                ).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            ) {
                                CommunityImage(
                                    title.artwork,
                                    null,
                                    Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                                Surface(
                                    Modifier.align(Alignment.TopStart).padding(8.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = accent,
                                ) {
                                    Text(
                                        "${profile.favorites.indexOf(title) + 1}",
                                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Text(title.title, maxLines = 3, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Liste", "Bacheca", "Attività").forEachIndexed { index, name ->
                    item { FilterChip(section == index, { section = index }, label = { Text(name, maxLines = 1) }) }
                }
            }
        }
        when (section) {
            0 -> {
                if (mine && !preview) {
                    item {
                        OutlinedButton(
                            onClick = onEditLists,
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Add, null)
                            Text(" Aggiungi e organizza titoli")
                        }
                    }
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(ShelfStatus.entries) { shelf ->
                            FilterChip(status == shelf, {
                                status = shelf
                            }, label = { Text(shelfNames.getValue(shelf)) })
                        }
                    }
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("Tutti", "Video", "Manga").forEachIndexed { index, label ->
                            item {
                                FilterChip(medium == index, { medium = index }, label = { Text(label, maxLines = 1) })
                            }
                        }
                    }
                }
                if (titles.isEmpty()) {
                    item {
                        EmptyStory(
                            Icons.Outlined.Bookmarks,
                            "Ogni lista comincia con una storia",
                            if (mine) "Tocca Aggiungi e scegli le copertine da mostrare. La tua libreria resta privata." else "Nessun titolo pubblicato in questa lista.",
                        )
                    }
                }
                items(titles, key = { it.id }) { TitleTile(it, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
            }
            1 -> {
                if (mine && !preview) {
                    item {
                        TextButton(onClick = onEditWall, modifier = Modifier.padding(horizontal = 20.dp)) {
                            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                            Text(" Chi può scrivere qui?")
                        }
                    }
                }
                if (!preview &&
                    (
                        mine ||
                            profile.wall == WallAccess.Everyone ||
                            profile.wall == WallAccess.Friends &&
                            state.isFriend(profile.key)
                        )
                ) {
                    item {
                        OutlinedButton(
                            onClick = onPost,
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        ) {
                            Text("Lascia un pensiero o uno sticker")
                        }
                    }
                }
                val posts = state.posts.filter {
                    it.wall == profile.key && it.reply.isEmpty()
                }.sortedWith(compareByDescending<CommunityItem> { it.pinned }.thenByDescending { it.at })
                if (posts.isEmpty()) {
                    item {
                        EmptyStory(
                            Icons.Outlined.FavoriteBorder,
                            "Un piccolo ricordo, lasciato qui",
                            if (profile.wall ==
                                WallAccess.Friends
                            ) {
                                "Questa bacheca è aperta agli amici."
                            } else if (profile.wall ==
                                WallAccess.Closed
                            ) {
                                "La bacheca è chiusa a nuovi messaggi."
                            } else {
                                "La bacheca è aperta a tutti."
                            },
                        )
                    }
                }
                items(posts, key = { it.id }) { PostCard(it, state, manager, onProfile, onReply) }
            }
            else -> {
                val posts = state.posts.filter {
                    it.author == profile.key && it.wall.isEmpty() && it.reply.isEmpty()
                }.sortedByDescending { it.at }
                if (posts.isEmpty()) {
                    item {
                        EmptyStory(
                            Icons.Outlined.AutoAwesome,
                            "Solo ciò che scegli di raccontare",
                            "Qui appaiono i contenuti condivisi, mai la cronologia privata.",
                        )
                    }
                }
                items(posts, key = { it.id }) { PostCard(it, state, manager, onProfile, onReply) }
            }
        }
    }
}

@Composable
internal fun FavoritesEditor(favorites: List<PublicTitle>, onChange: (List<PublicTitle>) -> Unit) {
    val threshold = with(LocalDensity.current) { 62.dp.toPx() }
    val current by rememberUpdatedState(favorites)
    val move = { id: String, delta: Int ->
        val list = current.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val target = if (index >= 0) (index + delta).coerceIn(0, list.lastIndex) else -1
        if (index >= 0 && index != target) {
            val item = list.removeAt(index)
            list.add(target, item)
            onChange(list)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        favorites.forEachIndexed { index, title ->
            Column(
                Modifier.fillMaxWidth().pointerInput(title.id) {
                    var distance = 0f
                    detectDragGesturesAfterLongPress(onDragStart = { distance = 0f }, onDrag = { change, amount ->
                        change.consume()
                        distance += amount.y
                        if (kotlin.math.abs(distance) >=
                            threshold
                        ) {
                            move(title.id, if (distance > 0) 1 else -1)
                            distance = 0f
                        }
                    })
                }.padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CommunityImage(title.artwork, null, Modifier.size(40.dp, 58.dp).clip(RoundedCornerShape(8.dp)))
                    Text(
                        title.title,
                        Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DragHandle, "Tieni premuto per riordinare")
                    Text(
                        "  ${index + 1} di 3",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { move(title.id, -1) }, enabled = index > 0) {
                        Icon(Icons.Outlined.ArrowUpward, "Sposta prima")
                    }
                    IconButton(onClick = { onChange(favorites - title) }) {
                        Icon(Icons.Outlined.Close, "Rimuovi dai preferiti pubblici")
                    }
                }
            }
        }
    }
}
