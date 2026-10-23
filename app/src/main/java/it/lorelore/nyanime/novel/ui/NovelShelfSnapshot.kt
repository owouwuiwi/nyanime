package it.lorelore.nyanime.novel.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.domain.base.BasePreferences
import it.lorelore.nyanime.novel.NovelDownloadStore
import it.lorelore.nyanime.novel.NovelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

internal data class NovelShelfEntry(
    val item: NovelStore.ShelfItem,
    val cover: NovelCover,
    val downloads: Int = 0,
    val downloading: Boolean = false,
) {
    val id
        get() = item.book.id

    val fraction
        get() = item.position?.totalProgression

    val inProgress
        get() = item.position != null && fraction != 1.0
}

internal enum class NovelShelfKind {
    COLLECTION,
    HISTORY,
    RESUME,
}

internal data class NovelShelfQuery(
    val query: String,
    val kind: NovelShelfKind,
    val inProgressOnly: Boolean,
    val localFilesOnly: Boolean,
)

internal data class NovelShelfSnapshot(
    val request: NovelShelfQuery,
    val entries: List<NovelShelfEntry> = emptyList(),
    val total: Int = 0,
    val more: Boolean = false,
    val loading: Boolean = true,
    val error: Boolean = false,
)

/** One bounded local query path for library, resume and history; no reader or network. */
@Composable
@OptIn(FlowPreview::class)
internal fun rememberNovelShelf(
    query: String,
    requested: Int,
    retry: Int,
    enabled: Boolean = true,
    inProgressOnly: Boolean = false,
    kind: NovelShelfKind = NovelShelfKind.COLLECTION,
    retainWhenDisabled: Boolean = false,
): NovelShelfSnapshot {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember(context) { NovelStore.shared(context) }
    val downloads = remember { Injekt.get<NovelDownloadStore>() }
    val localFilesOnly by remember {
        Injekt.get<BasePreferences>().downloadedOnly()
    }
        .collectAsState()
    val request = NovelShelfQuery(query, kind, inProgressOnly, localFilesOnly)
    val snapshot by
        produceState(
            NovelShelfSnapshot(request),
            context,
            lifecycle,
            store,
            request,
            requested,
            retry,
            enabled,
            retainWhenDisabled,
        ) {
            if (!enabled) {
                value =
                    if (retainWhenDisabled && value.request == request) {
                        value.copy(loading = false)
                    } else {
                        NovelShelfSnapshot(request, loading = false)
                    }
                return@produceState
            }
            value =
                if (value.request == request) {
                    value.copy(loading = true, error = false)
                } else {
                    NovelShelfSnapshot(request)
                }
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    store.changes,
                    downloads.changes.debounce(250).onStart { emit(downloads.changes.value) },
                ) { first, second ->
                    first to second
                }
                    .collectLatest {
                        try {
                            value = loadNovelShelf(context, store, request, requested)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            value = value.copy(loading = false, error = true)
                        }
                    }
            }
        }
    // A newer query or scope must never briefly expose the old query's results.
    return if ((enabled || retainWhenDisabled) && snapshot.request == request) {
        snapshot
    } else {
        NovelShelfSnapshot(request, loading = enabled)
    }
}

private suspend fun loadNovelShelf(
    context: Context,
    store: NovelStore,
    request: NovelShelfQuery,
    requested: Int,
): NovelShelfSnapshot =
    withContext(Dispatchers.IO) {
        require(requested > 0)
        if (!context.getDatabasePath(NovelStore.DATABASE_NAME).isFile) {
            return@withContext NovelShelfSnapshot(request, loading = false)
        }
        val rows = mutableListOf<NovelStore.ShelfItem>()
        var more: Boolean
        var total: Int
        var titleCursor: NovelStore.Cursor? = null
        var historyCursor: NovelStore.ReadingCursor? = null
        do {
            val size = minOf(40, requested - rows.size).coerceAtLeast(1)
            if (request.kind == NovelShelfKind.COLLECTION) {
                val page =
                    store.page(
                        request.query,
                        titleCursor,
                        size,
                        request.inProgressOnly,
                        request.localFilesOnly,
                    )
                rows += page.items
                titleCursor = page.next
                more = titleCursor != null
                total = page.total
            } else {
                val page =
                    store.readingPage(
                        query = request.query,
                        after = historyCursor,
                        size = size,
                        inProgressOnly =
                        request.inProgressOnly || request.kind == NovelShelfKind.RESUME,
                        favoritesOnly = request.kind == NovelShelfKind.RESUME,
                        localFilesOnly = request.localFilesOnly,
                    )
                rows += page.items
                historyCursor = page.next
                more = historyCursor != null
                total = page.total
            }
        } while (more && rows.size < requested)
        val root = File(context.filesDir, "novels").canonicalFile
        val entries = rows.map { item ->
            val cover =
                item.book.coverFile?.let { relative ->
                    val file = File(root, relative).canonicalFile
                    require(file.toPath().startsWith(root.toPath()) && file != root)
                    file.takeIf(File::isFile)
                }
            val progress =
                if (item.book.format == it.lorelore.nyanime.novel.NovelFormat.ONLINE) {
                    Injekt.get<NovelDownloadStore>().progress(item.book.id)
                } else {
                    null
                }
            NovelShelfEntry(
                item,
                NovelCover(item.book.id, cover, item.book.contentVersion),
                progress?.completedChapters ?: 0,
                progress?.state in
                    setOf(
                        it.lorelore.nyanime.novel.NovelDownloadState.RUNNING,
                        it.lorelore.nyanime.novel.NovelDownloadState.QUEUED,
                    ),
            )
        }
        NovelShelfSnapshot(request, entries, total, more, loading = false)
    }
