package eu.kanade.tachiyomi.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryTab
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.api.get
import eu.kanade.presentation.util.Tab as AppTab

/** One personal collection destination; discovery belongs to Home. */
data object LibrariesTab : AppTab {
    private val selectedMedium = MutableStateFlow(LibraryMedium.All)

    fun showAll() {
        selectedMedium.value = LibraryMedium.All
    }

    fun showAnime() {
        selectedMedium.value = LibraryMedium.Anime
    }

    fun showManga() {
        selectedMedium.value = LibraryMedium.Manga
    }

    fun showNovels() {
        selectedMedium.value = LibraryMedium.Novel
    }

    override val options: TabOptions
        @Composable get() = TabOptions(
            index = 1u,
            title = stringResource(MR.strings.label_library),
            icon = rememberVectorPainter(Icons.Outlined.CollectionsBookmark),
        )

    @Composable
    override fun Content() {
        val medium by selectedMedium.collectAsState()
        val modern = LocalNyanimeStyle.current
        LaunchedEffect(modern) {
            if (modern) {
                it.lorelore.nyanime.presentation.library.initializeLibraryLayout(
                    uy.kohesive.injekt.Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>(),
                )
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                if (modern) {
                    ModernLibraryHeader(
                        medium = medium,
                        onAll = ::showAll,
                        onAnime = ::showAnime,
                        onManga = ::showManga,
                        onNovel = ::showNovels,
                    )
                } else {
                    LegacyLibraryHeader(
                        medium = medium,
                        onAll = ::showAll,
                        onAnime = ::showAnime,
                        onManga = ::showManga,
                        onNovel = ::showNovels,
                    )
                }
                LibraryMediumContent(medium)
            }
        }
    }
}

private enum class LibraryMedium {
    All,
    Anime,
    Manga,
    Novel,
}

@Composable
private fun ModernLibraryHeader(
    medium: LibraryMedium,
    onAll: () -> Unit,
    onAnime: () -> Unit,
    onManga: () -> Unit,
    onNovel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
            androidx.compose.ui.res.stringResource(eu.kanade.tachiyomi.R.string.library_your_space),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(MR.strings.label_library),
            Modifier.padding(top = 5.dp, bottom = 20.dp),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
        )
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                LibraryMediumButton(
                    androidx.compose.ui.res.stringResource(eu.kanade.tachiyomi.R.string.library_medium_all),
                    medium == LibraryMedium.All,
                    onAll,
                    Modifier.weight(1f),
                )
                LibraryMediumButton(
                    "Anime",
                    medium == LibraryMedium.Anime,
                    onAnime,
                    Modifier.weight(1f),
                )
                LibraryMediumButton(
                    "Manga",
                    medium == LibraryMedium.Manga,
                    onManga,
                    Modifier.weight(1f),
                )
                LibraryMediumButton(
                    androidx.compose.ui.res.stringResource(eu.kanade.tachiyomi.R.string.novel_library),
                    medium == LibraryMedium.Novel,
                    onNovel,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LibraryMediumButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(13.dp)
    Surface(
        modifier = modifier.clip(shape).selectable(selected = selected, role = Role.Tab, onClick = onClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = null,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun LegacyLibraryHeader(
    medium: LibraryMedium,
    onAll: () -> Unit,
    onAnime: () -> Unit,
    onManga: () -> Unit,
    onNovel: () -> Unit,
) {
    Text(
        stringResource(MR.strings.label_library),
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.headlineMedium,
    )
    PrimaryTabRow(selectedTabIndex = medium.ordinal) {
        Tab(
            selected = medium == LibraryMedium.All,
            onClick = onAll,
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            text = { Text(androidx.compose.ui.res.stringResource(eu.kanade.tachiyomi.R.string.library_medium_all)) },
        )
        Tab(
            selected = medium == LibraryMedium.Anime,
            onClick = onAnime,
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            text = { Text("Anime") },
        )
        Tab(
            selected = medium == LibraryMedium.Manga,
            onClick = onManga,
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            text = { Text("Manga") },
        )
        Tab(
            selected = medium == LibraryMedium.Novel,
            onClick = onNovel,
            text = { Text(androidx.compose.ui.res.stringResource(eu.kanade.tachiyomi.R.string.novel_library)) },
        )
    }
}

@Composable
private fun LibrariesTab.LibraryMediumContent(medium: LibraryMedium) {
    val motion = appMotionEnabled()
    val enterMillis = if (motion) ModernMotion.RESIZE_MILLIS else 0
    val exitMillis = if (motion) ModernMotion.EXIT_MILLIS else 0
    AnimatedContent(
        targetState = medium,
        transitionSpec = {
            val enter = fadeIn(tween(enterMillis))
            val exit = fadeOut(tween(exitMillis))
            (enter togetherWith exit).using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> snap() }))
        },
        modifier = Modifier.fillMaxSize(),
        label = "personal-libraries",
    ) { target ->
        when (target) {
            LibraryMedium.All -> AllLibrariesContent()
            LibraryMedium.Anime -> AnimeLibraryTab.Content()
            LibraryMedium.Manga -> MangaLibraryTab.LibraryContent()
            LibraryMedium.Novel -> it.lorelore.nyanime.novel.ui.NovelShelfContent()
        }
    }
}
