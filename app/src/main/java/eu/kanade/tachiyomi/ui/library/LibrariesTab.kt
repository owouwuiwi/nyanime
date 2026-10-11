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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
import eu.kanade.presentation.util.Tab as AppTab

/** One personal collection destination; discovery belongs to Home. */
data object LibrariesTab : AppTab {
    private val selectedMedium = MutableStateFlow(LibraryMedium.Anime)

    fun showAll() {
        selectedMedium.value = LibraryMedium.All
    }

    fun showAnime() {
        selectedMedium.value = LibraryMedium.Anime
    }

    fun showManga() {
        selectedMedium.value = LibraryMedium.Manga
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
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                if (modern) {
                    ModernLibraryHeader(
                        medium = medium,
                        onAll = ::showAll,
                        onAnime = ::showAnime,
                        onManga = ::showManga,
                    )
                } else {
                    LegacyLibraryHeader(
                        medium = medium,
                        onAll = ::showAll,
                        onAnime = ::showAnime,
                        onManga = ::showManga,
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
}

@Composable
private fun ModernLibraryHeader(
    medium: LibraryMedium,
    onAll: () -> Unit,
    onAnime: () -> Unit,
    onManga: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(MR.strings.label_library),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(3.dp),
            ) {
                LibraryMediumButton(
                    label = androidx.compose.ui.res.stringResource(eu.kanade.tachiyomi.R.string.library_medium_all),
                    selected = medium == LibraryMedium.All,
                    accent = Color(0xFFB48CFF),
                    onClick = onAll,
                )
                LibraryMediumButton(
                    label = "Anime",
                    selected = medium == LibraryMedium.Anime,
                    accent = Color(0xFFFF9A5A),
                    onClick = onAnime,
                )
                LibraryMediumButton(
                    label = "Manga",
                    selected = medium == LibraryMedium.Manga,
                    accent = Color(0xFF63BFE8),
                    onClick = onManga,
                )
            }
        }
    }
}

@Composable
private fun LibraryMediumButton(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(13.dp)
    Surface(
        modifier = modifier.clip(shape).clickable(onClick = onClick),
        shape = shape,
        color = if (selected) accent.copy(alpha = .20f) else Color.Transparent,
        contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) BorderStroke(1.dp, accent.copy(alpha = .55f)) else null,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
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
        }
    }
}
