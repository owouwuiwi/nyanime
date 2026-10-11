@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.community

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.data.community.CommunityInteractions
import eu.kanade.tachiyomi.data.community.CommunityProfile
import eu.kanade.tachiyomi.data.community.CommunityState
import eu.kanade.tachiyomi.data.community.PublicTitle
import eu.kanade.tachiyomi.data.community.ShelfStatus
import eu.kanade.tachiyomi.data.community.WallAccess
import eu.kanade.tachiyomi.data.community.validDraft

/** The primary action stays visible; title picking and preview each own a single scrolling surface. */
@Composable
internal fun CommunityEditorFrame(
    title: String,
    detail: String,
    close: () -> Unit,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().fillMaxHeight(.96f).navigationBarsPadding().imePadding(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Chiudi") }
            }
            Column(Modifier.weight(1f).fillMaxWidth(), content = content)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) { footer() }
        }
    }
}

@Composable
internal fun ProfileStudio(
    state: CommunityState,
    profile: CommunityProfile,
    interactions: CommunityInteractions,
    initialSection: Int = 0,
    change: (CommunityProfile) -> Unit,
    publish: () -> Unit,
    close: () -> Unit,
    imageEditor: @Composable (String, String, (String) -> Unit) -> Unit,
) {
    var section by rememberSaveable { mutableIntStateOf(initialSection) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var shelf by rememberSaveable { mutableStateOf(ShelfStatus.Planned) }
    val motion = modernMotionEnabled()
    val accent = Color(profile.accent)
    val catalog = remember(state.library) { state.library.map { it.publicTitle() } }
    val back = {
        if (picking) {
            picking = false
        } else if (preview) {
            preview = false
        } else {
            close()
        }
    }
    BackHandler { back() }
    CommunityEditorFrame(
        title = when {
            preview -> "Il tuo profilo pubblico"
            picking -> if (section ==
                1
            ) {
                "Scegli i tuoi preferiti"
            } else {
                "Aggiungi titoli"
            }
            else -> "Il tuo spazio"
        },
        detail = when {
            preview -> "Controlla ciò che vedranno gli altri"
            picking -> "Tocca le copertine per selezionarle"
            else -> "Le modifiche restano private fino alla pubblicazione"
        },
        close = close,
        footer = {
            if (picking) {
                Button(onClick = { picking = false }, modifier = Modifier.fillMaxWidth()) { Text("Conferma selezione") }
            } else if (preview) {
                Button(
                    onClick = publish,
                    enabled = profile.validDraft() && !state.publishing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Pubblica le modifiche")
                }
                TextButton(onClick = {
                    preview = false
                }, modifier = Modifier.fillMaxWidth()) { Text("Torna alle modifiche") }
            } else {
                Button(onClick = {
                    preview = true
                }, enabled = profile.validDraft() && !state.publishing, modifier = Modifier.fillMaxWidth()) {
                    Text("Vedi anteprima")
                }
            }
        },
    ) {
        if (!preview && !picking) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Aspetto", "Top 3", "Liste", "Bacheca").forEachIndexed { index, label ->
                    item { FilterChip(section == index, { section = index }, label = { Text(label) }) }
                }
            }
        }
        AnimatedContent(
            targetState = when {
                preview -> 5
                picking -> 4
                else -> section
            },
            transitionSpec = { ModernMotion.transform(motion) },
            label = "profile-editor",
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                5 -> ProfilePage(state, profile, interactions, {}, {}, {}, {}, {}, {}, preview = true)
                4 -> CommunityTitlePicker(
                    titles = catalog + profile.shelves + profile.favorites,
                    selected = (
                        if (section ==
                            1
                        ) {
                            profile.favorites
                        } else {
                            profile.shelves.filter { it.status == shelf }
                        }
                        ).map { it.id }.toSet(),
                    limit = if (section == 1) 3 else 250 - profile.shelves.count { it.status != shelf },
                    onBack = { picking = false },
                    choose = { title ->
                        if (section == 1) {
                            change(
                                profile.copy(
                                    favorites = if (profile.favorites.any { it.id == title.id }) {
                                        profile.favorites.filterNot {
                                            it.id ==
                                                title.id
                                        }
                                    } else {
                                        (profile.favorites + title).take(3)
                                    },
                                ),
                            )
                        } else {
                            change(
                                profile.copy(
                                    shelves = if (profile.shelves.any { it.id == title.id && it.status == shelf }) {
                                        profile.shelves.filterNot {
                                            it.id ==
                                                title.id
                                        }
                                    } else {
                                        (
                                            profile.shelves.filterNot {
                                                it.id == title.id
                                            } +
                                                title.copy(status = shelf)
                                            ).take(250)
                                    },
                                ),
                            )
                        }
                    },
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    when (page) {
                        0 -> {
                            item {
                                Surface(shape = RoundedCornerShape(24.dp)) {
                                    Box(
                                        Modifier.fillMaxWidth().height(
                                            172.dp,
                                        ).background(
                                            Brush.linearGradient(
                                                listOf(
                                                    accent.copy(alpha = .65f),
                                                    MaterialTheme.colorScheme.surfaceContainer,
                                                ),
                                            ),
                                        ),
                                    ) {
                                        CommunityImage(profile.banner, null, Modifier.fillMaxSize())
                                        Box(
                                            Modifier.fillMaxSize().background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Transparent, Color.Black.copy(alpha = .65f)),
                                                ),
                                            ),
                                        )
                                        Row(
                                            Modifier.align(Alignment.BottomStart).padding(18.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                            Avatar(profile, 64)
                                            Text(
                                                profile.name.ifBlank {
                                                    "Il tuo nome"
                                                },
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 2,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                OutlinedTextField(profile.name, {
                                    change(profile.copy(name = it.take(40)))
                                }, label = {
                                    Text("Come ti chiami?")
                                }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                            item {
                                OutlinedTextField(profile.bio, {
                                    change(profile.copy(bio = it.take(800)))
                                }, label = {
                                    Text("Qualcosa di te")
                                }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                            }
                            item { imageEditor("Foto profilo", profile.avatar) { change(profile.copy(avatar = it)) } }
                            item { imageEditor("Copertina", profile.banner) { change(profile.copy(banner = it)) } }
                            item {
                                Text("Il tuo colore", style = MaterialTheme.typography.titleMedium)
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(top = 10.dp),
                                ) {
                                    items(listOf(0xFFE50934, 0xFFAC6CFF, 0xFFEC6EAD, 0xFF319C9A, 0xFFC98A39)) { color ->
                                        Surface(onClick = {
                                            change(profile.copy(accent = color))
                                        }, color = Color(color), shape = RoundedCornerShape(16.dp)) {
                                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                                if (profile.accent ==
                                                    color
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.Check,
                                                        "Colore selezionato",
                                                        tint = Color.White,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            item {
                                StudioHeading(
                                    "Tre storie che parlano di te",
                                    "Scegli fino a tre titoli. Trascina le righe per cambiare l’ordine.",
                                )
                            }
                            item { FavoritesEditor(profile.favorites) { change(profile.copy(favorites = it)) } }
                            item {
                                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Outlined.Star, null)
                                    Text(" Scegli titoli · ${profile.favorites.size}/3")
                                }
                            }
                        }
                        2 -> {
                            item {
                                StudioHeading(
                                    "La tua vetrina, a modo tuo",
                                    "Qui scegli solo i titoli da mostrare. La tua libreria personale resta privata.",
                                )
                            }
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(ShelfStatus.entries) { status ->
                                        FilterChip(shelf == status, {
                                            shelf = status
                                        }, label = {
                                            Text(
                                                "${shelfNames.getValue(status)} · ${profile.shelves.count {
                                                    it.status == status
                                                }}",
                                            )
                                        })
                                    }
                                }
                            }
                            item {
                                Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Outlined.Add, null)
                                    Text(" Aggiungi a ${shelfNames.getValue(shelf).lowercase()}")
                                }
                            }
                            items(profile.shelves.filter { it.status == shelf }, key = { it.id }) { title ->
                                StudioShelfTitle(title, remove = {
                                    change(
                                        profile.copy(
                                            shelves = profile.shelves.filterNot {
                                                it.id ==
                                                    title.id
                                            },
                                        ),
                                    )
                                }) { status ->
                                    change(
                                        profile.copy(
                                            shelves = profile.shelves.map {
                                                if (it.id ==
                                                    title.id
                                                ) {
                                                    it.copy(status = status)
                                                } else {
                                                    it
                                                }
                                            },
                                        ),
                                    )
                                }
                            }
                            if (profile.shelves.none {
                                    it.status == shelf
                                }
                            ) {
                                item {
                                    Text(
                                        "Questa lista aspetta le tue storie.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        3 -> {
                            item {
                                StudioHeading(
                                    "Lascia spazio ai tuoi amici",
                                    "Decidi chi può lasciare pensieri, consigli e sticker sulla tua bacheca.",
                                )
                            }
                            items(WallAccess.entries) { access ->
                                Surface(
                                    onClick = {
                                        change(profile.copy(wall = access))
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (profile.wall ==
                                        access
                                    ) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(profile.wall == access, null)
                                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                            Text(
                                                when (access) {
                                                    WallAccess.Friends -> "Solo i miei amici"
                                                    WallAccess.Everyone -> "Tutti"
                                                    WallAccess.Closed -> "Solo io"
                                                },
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                when (access) {
                                                    WallAccess.Friends -> "Il tuo salotto tra persone che conosci"
                                                    WallAccess.Everyone -> "Anche chi scopre il tuo profilo"
                                                    WallAccess.Closed -> "Gli altri possono leggere, senza scrivere"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                Text(
                                    "Puoi fissare i messaggi preferiti e nascondere quelli indesiderati dalla bacheca.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioHeading(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StudioShelfTitle(title: PublicTitle, remove: () -> Unit, status: (ShelfStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CommunityImage(title.artwork, null, Modifier.size(46.dp, 66.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f)) {
                    Text(title.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp))
                        Text(" ${shelfNames.getValue(title.status)}")
                    }
                }
                IconButton(onClick = remove) { Icon(Icons.Outlined.Close, "Rimuovi dalla lista pubblica") }
            }
            if (expanded) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ShelfStatus.entries) { value ->
                        FilterChip(value == title.status, {
                            status(value)
                            expanded =
                                false
                        }, label = { Text(shelfNames.getValue(value)) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun CommunityTitlePicker(
    titles: List<PublicTitle>,
    selected: Set<String>,
    limit: Int,
    onBack: () -> Unit,
    singleChoice: Boolean = false,
    choose: (PublicTitle) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var medium by rememberSaveable { mutableIntStateOf(0) }
    val available = remember(titles) { titles.distinctBy { it.id } }
    val matches = remember(available, query, medium) {
        available.filter {
            (medium == 0 || it.manga == (medium == 2)) &&
                it.title.contains(query.trim(), true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Indietro") }
            OutlinedTextField(query, {
                query = it
            }, singleLine = true, placeholder = {
                Text("Cerca un titolo")
            }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.weight(1f))
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Tutti", "Video", "Manga").forEachIndexed { index, label ->
                item {
                    FilterChip(medium == index, {
                        medium =
                            index
                    }, label = { Text(label) })
                }
            }
        }
        Text(
            "${selected.size} selezionati${if (limit < 250) " · massimo $limit" else ""}",
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (matches.isEmpty()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (available.isEmpty()) "Le tue storie compariranno qui" else "Nessun titolo trovato",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (available.isEmpty()) "Apri un titolo nell’app o aggiungilo alla libreria, poi sceglilo qui." else "Prova un altro nome o cambia il filtro.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (LocalDensity.current.fontScale > 1.3f) 132.dp else 102.dp),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(matches, key = { it.id }) { title ->
                val chosen = title.id in selected
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable(
                        enabled =
                        singleChoice || chosen || selected.size < limit,
                    ) {
                        choose(title)
                    },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(
                            .68f,
                        ).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        CommunityImage(title.artwork, null, Modifier.fillMaxSize())
                        Surface(
                            Modifier.align(Alignment.TopEnd).padding(8.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = if (chosen) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .65f),
                        ) {
                            Icon(
                                if (chosen) Icons.Outlined.Check else Icons.Outlined.Add,
                                if (chosen) "Selezionato" else "Aggiungi",
                                Modifier.padding(6.dp).size(20.dp),
                                tint = Color.White,
                            )
                        }
                    }
                    Text(
                        title.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
