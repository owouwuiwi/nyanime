package it.lorelore.nyanime.novel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import it.lorelore.nyanime.novel.NovelBook
import it.lorelore.nyanime.novel.NovelChapterGroups
import it.lorelore.nyanime.novel.NovelDownloadProgress
import it.lorelore.nyanime.novel.NovelIdentity
import it.lorelore.nyanime.novel.NovelLibrary
import it.lorelore.nyanime.novel.NovelOnlineDocument
import it.lorelore.nyanime.novel.NovelPosition
import it.lorelore.nyanime.novel.download.NovelDownloads
import it.lorelore.nyanime.novel.source.NovelOnlineRepository
import it.lorelore.nyanime.novel.source.NovelPreparationPhase
import it.lorelore.nyanime.novel.source.NovelPreparationProgress
import it.lorelore.nyanime.source.novel.NovelDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal data class NovelDetailsState(
    val pendingDetails: NovelDetails? = null,
    val preparation: NovelPreparationProgress? = null,
    val book: NovelBook? = null,
    val document: NovelOnlineDocument? = null,
    val position: NovelPosition? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val saveFailed: Boolean = false,
    val download: NovelDownloadProgress? = null,
    val downloadBusy: Boolean = false,
    val downloadFailed: Boolean = false,
)

/** Only explicit entry to this screen refreshes metadata. It does not fetch chapter bodies. */
private class NovelDetailsModel(private val sourceId: Long, private val reference: String) :
    StateScreenModel<NovelDetailsState>(NovelDetailsState()) {
    private val library = Injekt.get<NovelLibrary>()
    private val repository = Injekt.get<NovelOnlineRepository>()
    private val downloads = Injekt.get<NovelDownloads>()
    private val id = NovelIdentity.remote(sourceId, reference)
    private var request = 0
    private var job: Job? = null

    init {
        startRefresh(false)
        screenModelScope.launch {
            try {
                downloads.store.observe(id).collect { progress ->
                    mutableState.update { it.copy(download = progress) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(downloadFailed = true) }
            }
        }
    }

    fun local() {
        screenModelScope.launch {
            val book = library.store.get(id) ?: return@launch
            val position =
                library.store.position(id)?.takeIf { it.contentVersion == book.contentVersion }
            mutableState.update { current ->
                if (current.book?.modifiedAt?.let { it > book.modifiedAt } == true) {
                    current
                } else {
                    current.copy(book = book, position = position)
                }
            }
        }
    }

    fun refresh() = startRefresh(true)

    private fun startRefresh(force: Boolean) {
        val ticket = ++request
        job?.cancel()
        mutableState.update { it.copy(loading = true, failed = false) }
        job = screenModelScope.launch {
            try {
                library.store.get(id)?.let { book ->
                    val document = repository.document(book)
                    val position = library.store.position(id)
                    if (ticket == request) {
                        mutableState.update {
                            it.copy(
                                book = book,
                                document = document,
                                position = position,
                            )
                        }
                    }
                }
                val book =
                    repository.refresh(
                        sourceId,
                        reference,
                        forceRefresh = force,
                        onDetails = { details ->
                            if (ticket == request) {
                                mutableState.update { it.copy(pendingDetails = details) }
                            }
                        },
                        onPreparation = { progress ->
                            if (ticket == request) {
                                mutableState.update { it.copy(preparation = progress) }
                            }
                        },
                    )
                val document = repository.document(book)
                val position =
                    library.store.position(id)?.takeIf { it.contentVersion == book.contentVersion }
                if (ticket == request) {
                    mutableState.update {
                        it.copy(book = book, document = document, position = position)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (ticket == request) mutableState.update { it.copy(failed = true) }
            } finally {
                if (ticket == request) mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun pausePreparation() {
        screenModelScope.launch {
            try {
                repository.pausePreparation(sourceId, reference)
                mutableState.update { current ->
                    if (
                        current.preparation?.phase !in
                        setOf(NovelPreparationPhase.RUNNING, NovelPreparationPhase.WAITING)
                    ) {
                        current
                    } else {
                        current.copy(
                            preparation =
                            current.preparation?.copy(phase = NovelPreparationPhase.PAUSED),
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(failed = true) }
            }
        }
    }

    fun favorite() {
        val book = state.value.book ?: return
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true, saveFailed = false) }
        library.commit {
            try {
                library.store.setFavorite(id, !book.favorite)
                val current = library.store.get(id)
                mutableState.update { it.copy(book = current ?: it.book) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(saveFailed = true) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    fun download(selected: List<String>) {
        val book = state.value.book ?: return
        val document = state.value.document ?: return
        downloadOperation { downloads.enqueue(book, document, selected) }
    }

    fun pauseDownload() = downloadOperation { downloads.pause(id) }

    fun retryDownload() = downloadOperation { downloads.retry(id) }

    fun removeDownload() = downloadOperation { downloads.remove(id) }

    private fun downloadOperation(operation: suspend () -> Unit) {
        if (state.value.downloadBusy) return
        mutableState.update { it.copy(downloadBusy = true, downloadFailed = false) }
        library.commit {
            try {
                operation()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(downloadFailed = true) }
            } finally {
                mutableState.update { it.copy(downloadBusy = false) }
            }
        }
    }
}

class NovelDetailsScreen(
    private val sourceId: Long,
    private val reference: String,
    private val selectDownload: Boolean = false,
) : Screen() {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { NovelDetailsModel(sourceId, reference) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val motion = appMotionEnabled()
        var query by rememberSaveable { mutableStateOf("") }
        var chooseDownloads by rememberSaveable { mutableStateOf(selectDownload) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.local() }
        val book = state.book
        val document = state.document
        val pending = state.pendingDetails
        val displayTitle = pending?.entry?.title ?: book?.title
        val displayAuthors = pending?.entry?.authors ?: book?.authors.orEmpty()
        val chapters =
            remember(document, query) {
                document?.chapters.orEmpty().filter {
                    query.isBlank() ||
                        it.title.contains(query, ignoreCase = true) ||
                        it.group?.contains(query, ignoreCase = true) == true
                }
            }
        val chapterGroups = remember(chapters) { NovelChapterGroups.of(chapters) { it.group } }
        val grouped = chapterGroups.any { it.label != null }
        var expandedGroups by
            rememberSaveable(sourceId, reference) { mutableStateOf(listOf<String>()) }
        var initializedGroups by rememberSaveable(sourceId, reference) { mutableStateOf(false) }
        LaunchedEffect(document, state.position?.chapterReference) {
            if (!initializedGroups && document != null) {
                val all = NovelChapterGroups.of(document.chapters) { it.group }
                val current = state.position?.chapterReference?.substringBefore('#')
                expandedGroups =
                    listOfNotNull(NovelChapterGroups.initial(all) { it.href == current })
                initializedGroups = true
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize().privacyRegion(PrivacyArea.LIBRARY),
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton({ navigator?.pop() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.guide_back),
                        )
                    }
                    Text(
                        stringResource(R.string.novel_library),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(model::refresh, enabled = !state.loading) {
                        Text(stringResource(R.string.novel_refresh))
                    }
                }
            },
        ) { insets ->
            LazyColumn(
                Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()),
                contentPadding =
                PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom =
                    24.dp +
                        insets.calculateBottomPadding() +
                        LocalFloatingNavigationInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Box(Modifier.fillMaxWidth().height(4.dp)) {
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                item {
                    if (displayTitle == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Box(
                                Modifier.size(108.dp, 156.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                repeat(3) {
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .height(18.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                            ),
                                    )
                                }
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            AsyncImage(
                                model =
                                ImageRequest.Builder(context)
                                    .data(
                                        (pending?.entry?.cover ?: document?.coverReference)
                                            ?.let {
                                                NovelSourceCover(sourceId, it, reference)
                                            }
                                            ?: book?.let {
                                                NovelCover(
                                                    book.id,
                                                    book.coverFile?.let {
                                                        Injekt.get<NovelLibrary>().ownedFile(it)
                                                    },
                                                    book.contentVersion,
                                                )
                                            },
                                    )
                                    .crossfade(if (motion) 180 else 0)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                Modifier.size(108.dp, 156.dp).clip(RoundedCornerShape(16.dp)),
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(displayTitle, style = MaterialTheme.typography.headlineSmall)
                                if (displayAuthors.isNotEmpty()) {
                                    Text(
                                        displayAuthors.joinToString(" · "),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (document != null) {
                                    Text(
                                        pluralStringResource(
                                            R.plurals.novel_chapter_count,
                                            document.chapters.size,
                                            document.chapters.size,
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "chapter-preparation") {
                    NovelPreparationPanel(
                        state.preparation,
                        model::pausePreparation,
                        model::refresh,
                    )
                }
                if (book == null && pending?.description?.isNotBlank() == true) {
                    item { Text(pending.description, style = MaterialTheme.typography.bodyMedium) }
                }
                if (state.failed && state.preparation?.phase != NovelPreparationPhase.FAILED) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(
                                        if (book == null) {
                                            R.string.novel_online_unavailable
                                        } else {
                                            R.string.novel_saved_details
                                        },
                                    ),
                                )
                                TextButton(model::refresh, enabled = !state.loading) {
                                    Text(stringResource(R.string.novel_retry))
                                }
                            }
                        }
                    }
                }
                if (state.saveFailed) {
                    item {
                        Text(
                            stringResource(R.string.novel_storage_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (book != null) {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                {
                                    context.startActivity(
                                        NovelReaderActivity.intent(context, book.id),
                                    )
                                },
                                Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.AutoStories, null)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(
                                        if (state.position == null) {
                                            R.string.novel_start_reading
                                        } else {
                                            R.string.novel_resume
                                        },
                                    ),
                                )
                            }
                            OutlinedButton(model::favorite, enabled = !state.saving) {
                                Icon(
                                    if (book.favorite) {
                                        Icons.Outlined.Bookmark
                                    } else {
                                        Icons.Outlined.BookmarkBorder
                                    },
                                    stringResource(
                                        if (book.favorite) {
                                            R.string.novel_remove
                                        } else {
                                            R.string.novel_add_library
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    if (document != null) {
                        item {
                            NovelDownloadPanel(
                                state.download,
                                state.downloadBusy,
                                state.downloadFailed,
                                onChoose = { chooseDownloads = true },
                                onPause = model::pauseDownload,
                                onRetry = model::retryDownload,
                                onRemove = model::removeDownload,
                            )
                        }
                    }
                    if (book.description.isNotBlank()) {
                        item {
                            var expanded by rememberSaveable { mutableStateOf(false) }
                            var truncated by remember { mutableStateOf(false) }
                            Column {
                                Text(
                                    book.description,
                                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    onTextLayout = {
                                        if (!expanded) truncated = it.hasVisualOverflow
                                    },
                                )
                                if (expanded || truncated) {
                                    TextButton({ expanded = !expanded }) {
                                        Text(
                                            stringResource(
                                                if (expanded) {
                                                    R.string.novel_show_less
                                                } else {
                                                    R.string.novel_show_more
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.novel_chapters),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        OutlinedTextField(
                            query,
                            { query = it.take(256) },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.novel_find_chapter)) },
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        )
                    }
                    chapterGroups.forEach { group ->
                        val open = !grouped || query.isNotBlank() || group.key in expandedGroups
                        if (grouped) {
                            item(key = "group:${group.key}", contentType = "chapter-group") {
                                NovelChapterGroupHeader(
                                    group.label,
                                    group.entries.size,
                                    open,
                                    {
                                        if (query.isBlank()) {
                                            expandedGroups =
                                                if (group.key in expandedGroups) {
                                                    expandedGroups - group.key
                                                } else {
                                                    expandedGroups + group.key
                                                }
                                        }
                                    },
                                    enabled = query.isBlank(),
                                )
                            }
                        }
                        if (open) {
                            items(group.entries, key = { it.href }, contentType = { "chapter" }) { chapter ->
                                val current =
                                    state.position?.chapterReference?.substringBefore('#') ==
                                        chapter.href
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color =
                                    if (current) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    modifier =
                                    Modifier.fillMaxWidth().clickable {
                                        context.startActivity(
                                            NovelReaderActivity.intent(
                                                context,
                                                book.id,
                                                chapter.href,
                                            ),
                                        )
                                    },
                                ) {
                                    Column(
                                        Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            chapter.title.ifBlank {
                                                stringResource(R.string.novel_untitled_chapter)
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        if (current) {
                                            Text(
                                                stringResource(R.string.novel_saved_position),
                                                style = MaterialTheme.typography.labelSmall,
                                                color =
                                                MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (chapters.isEmpty() && !state.loading) {
                        item { Text(stringResource(R.string.novel_no_chapters)) }
                    }
                }
            }
        }
        if (chooseDownloads && document != null) {
            NovelDownloadSelection(
                document,
                state.position?.chapterReference?.substringBefore('#'),
                onDismiss = { chooseDownloads = false },
                onDownload = { selected ->
                    chooseDownloads = false
                    model.download(selected)
                },
            )
        }
    }
}
