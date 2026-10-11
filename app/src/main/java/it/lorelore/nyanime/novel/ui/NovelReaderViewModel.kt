package it.lorelore.nyanime.novel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.lorelore.nyanime.novel.NovelAnchors
import it.lorelore.nyanime.novel.NovelAnnotation
import it.lorelore.nyanime.novel.NovelAppearance
import it.lorelore.nyanime.novel.NovelBook
import it.lorelore.nyanime.novel.NovelFormat
import it.lorelore.nyanime.novel.NovelImporter
import it.lorelore.nyanime.novel.NovelLibrary
import it.lorelore.nyanime.novel.NovelPosition
import it.lorelore.nyanime.novel.NovelSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import java.io.InputStream
import java.nio.charset.Charset

internal sealed interface NovelReaderState {
    data class Preparing(val progress: NovelImporter.Progress? = null) : NovelReaderState

    data class Ready(val opened: NovelLibrary.Opened) : NovelReaderState

    data class Missing(val book: NovelBook) : NovelReaderState

    data class Failure(val cause: Throwable) : NovelReaderState
}

/** Owns one publication across configuration changes; the fragment never owns library data. */
internal class NovelReaderViewModel(
    private val library: NovelLibrary,
    private val incognito: () -> Boolean,
    private val onClosed: (List<Job>) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow<NovelReaderState>(NovelReaderState.Preparing())
    val state = _state.asStateFlow()
    private val _locator = MutableStateFlow<Locator?>(null)
    val locator = _locator.asStateFlow()
    private val _appearance = MutableStateFlow(NovelAppearance())
    val appearance = _appearance.asStateFlow()
    private val _perBook = MutableStateFlow(false)
    val perBook = _perBook.asStateFlow()
    private val _annotations = MutableStateFlow<List<NovelAnnotation>>(emptyList())
    val annotations = _annotations.asStateFlow()
    private val _chapterVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val chapterVersions = _chapterVersions.asStateFlow()
    private val _searchProgress = MutableStateFlow<NovelSearch.Progress?>(null)
    val searchProgress = _searchProgress.asStateFlow()
    private val _hits = MutableStateFlow<List<NovelSearch.Hit>>(emptyList())
    val hits = _hits.asStateFlow()
    private val _errors = MutableStateFlow<Throwable?>(null)
    val errors = _errors.asStateFlow()
    private val _editing = MutableStateFlow<NovelAnnotation?>(null)
    val editing = _editing.asStateFlow()
    var initialized = false
        private set

    init {
        viewModelScope.launch {
            library.writeFailures.collect { if (it != null) _errors.value = it }
        }
    }

    fun errorHandled(error: Throwable) {
        if (_errors.value === error) _errors.value = null
        library.acknowledgeWriteFailure(error)
    }

    private var loadJob: Job? = null
    private var annotationsJob: Job? = null
    private var chapterVersionsJob: Job? = null
    private var indexJob: Job? = null
    private var queryJob: Job? = null
    private var positionJob: Job? = null
    private var owned: NovelLibrary.Opened? = null
    private var generation = 0L
    private var appearanceRevision = 0L
    private var lastStamp = 0L
    private var query = ""
    private var searching = false
    private var searchBook: NovelBook? = null
    private var indexRevision = 0L
    private var pageReady = false

    class InvalidPositionException : IllegalArgumentException("Invalid reading position")

    fun awaitingPage(session: NovelLibrary.Opened) {
        if (owned === session) {
            positionJob?.cancel()
            pageReady = false
        }
    }

    fun rendered(session: NovelLibrary.Opened) {
        if (owned === session) pageReady = true
    }

    private fun validPoint(session: NovelLibrary.Opened, point: Locator): Boolean {
        val href = point.href.removeFragment().removeQuery()
        return session.publication.readingOrder.any {
            it.href.resolve().removeFragment().removeQuery() == href
        }
    }

    private fun compactPoint(point: Locator) =
        point.copy(
            text =
            Locator.Text(
                before = point.text.before?.takeLast(256),
                highlight = point.text.highlight?.take(1024),
                after = point.text.after?.take(256),
            ),
        )

    fun savedPoint(): String? {
        val session = owned ?: return null
        if (!pageReady) return null
        return _locator.value?.let { point ->
            runCatching {
                compactPoint(point).toJSON().toString().takeIf {
                    it.length <= 65536 && validPoint(session, point)
                }
            }
                .getOrNull()
        }
    }

    fun load(bookId: String, startHref: String? = null) {
        initialized = true
        if (_state.value is NovelReaderState.Ready && owned?.book?.id == bookId) return
        loadJob?.cancel()
        val request = ++generation
        closeCurrent()
        _state.value = NovelReaderState.Preparing()
        loadJob = viewModelScope.launch { open(bookId, request, startHref) }
    }

    fun import(
        format: NovelFormat,
        name: String,
        encoding: Charset?,
        openStream: () -> InputStream,
        partTitle: (Int) -> String,
        expectedId: String? = null,
    ) {
        initialized = true
        loadJob?.cancel()
        val request = ++generation
        closeCurrent()
        _state.value = NovelReaderState.Preparing()
        loadJob = viewModelScope.launch {
            try {
                val book =
                    library.importer.import(
                        openStream,
                        format,
                        name,
                        encoding,
                        partTitle,
                        expectedId,
                    ) {
                        if (request == generation) _state.value = NovelReaderState.Preparing(it)
                    }
                open(book.id, request)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (request == generation) _state.value = NovelReaderState.Failure(error)
            }
        }
    }

    fun encoding(charset: Charset, partTitle: (Int) -> String) {
        val book = owned?.book ?: return
        if (book.format != NovelFormat.TXT || book.textEncoding == charset.name()) return
        val original = book.originalFile?.let(library::ownedFile) ?: return
        import(
            NovelFormat.TXT,
            "${book.title}.txt",
            charset,
            { original.inputStream() },
            partTitle,
            book.id,
        )
    }

    private suspend fun open(bookId: String, request: Long, startHref: String? = null) {
        var candidate: NovelLibrary.Opened? = null
        try {
            candidate = library.open(bookId)
            val appearance = library.store.appearance(bookId)
            val global = library.store.appearance(null)
            if (request != generation) return
            owned = candidate
            candidate = null
            val current = owned!!
            lastStamp = current.position?.updatedAt ?: 0L
            _locator.value =
                current.position?.locatorJson?.let { json ->
                    runCatching {
                        Locator.fromJSON(JSONObject(json))
                            ?.takeIf { validPoint(current, it) }
                            ?.let {
                                // Restore the structural point, never an unverified online
                                // quotation.
                                if (current.online != null) it.copy(text = Locator.Text()) else it
                            }
                    }
                        .getOrNull()
                        .also { if (it == null) _errors.tryEmit(InvalidPositionException()) }
                }
            _appearance.value = appearance ?: global ?: NovelAppearance()
            if (startHref != null) {
                val link =
                    current.publication.readingOrder.firstOrNull {
                        it.href.resolve().toString() == startHref
                    }
                if (link != null) {
                    _locator.value =
                        Locator(
                            link.href.resolve(),
                            link.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.HTML,
                            link.title,
                        )
                } else {
                    _errors.tryEmit(InvalidPositionException())
                }
            }
            _perBook.value = appearance != null
            _chapterVersions.value = current.online?.chapterVersions?.value.orEmpty()
            _state.value = NovelReaderState.Ready(current)
            current.online?.let { online ->
                chapterVersionsJob = viewModelScope.launch {
                    online.chapterVersions.collect { _chapterVersions.value = it }
                }
            }
            annotationsJob = viewModelScope.launch {
                library.store
                    .observeAnnotations(bookId)
                    .catch { _errors.emit(it) }
                    .collect { _annotations.value = it }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (missing: NovelLibrary.MissingDocumentException) {
            if (request == generation) _state.value = NovelReaderState.Missing(missing.book)
        } catch (error: Exception) {
            if (request == generation) _state.value = NovelReaderState.Failure(error)
        } finally {
            candidate?.close()
        }
    }

    fun moved(locator: Locator) {
        val session = owned ?: return
        _locator.value = locator
        if (!pageReady) return
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            delay(500)
            persistPosition(session, locator)
        }
    }

    fun checkpoint() {
        positionJob?.cancel()
        val session = owned ?: return
        val current = _locator.value ?: return
        persistPosition(session, current)
    }

    private fun persistPosition(session: NovelLibrary.Opened, locator: Locator) {
        if (incognito() || !pageReady || owned !== session) return
        val chapter = NovelAnchors.chapter(locator.href.toString())
        val resourceVersion = session.online?.chapterVersions?.value?.get(chapter)
        if (session.online != null && resourceVersion == null) return
        lastStamp = it.lorelore.nyanime.novel.nextNovelTimestamp(lastStamp)
        val value = runCatching {
            require(validPoint(session, locator))
            NovelPosition(
                bookId = session.book.id,
                contentVersion = session.book.contentVersion,
                locatorJson = compactPoint(locator).toJSON().toString(),
                updatedAt = lastStamp,
                totalProgression = locator.locations.totalProgression,
                chapterReference = locator.href.toString(),
                resourceVersion = resourceVersion,
            )
        }
            .getOrElse {
                _errors.tryEmit(InvalidPositionException())
                return
            }
        val store = library.store
        persist { store.savePosition(value) }
    }

    fun previewAppearance(value: NovelAppearance) {
        appearanceRevision++
        _appearance.value = value
    }

    fun appearance(value: NovelAppearance) {
        appearanceRevision++
        val id = if (_perBook.value) owned?.book?.id ?: return else null
        _appearance.value = value
        val store = library.store
        persist { store.saveAppearance(id, value) }
    }

    fun perBook(enabled: Boolean) {
        val id = owned?.book?.id ?: return
        val request = generation
        val appearanceRequest = ++appearanceRevision
        val selected = _appearance.value
        _perBook.value = enabled
        persist {
            library.store.saveAppearance(id, selected.takeIf { enabled })
            if (!enabled) {
                val global = library.store.appearance(null) ?: NovelAppearance()
                withContext(Dispatchers.Main.immediate) {
                    if (request == generation && appearanceRevision == appearanceRequest) {
                        _appearance.value = global
                    }
                }
            }
        }
    }

    fun save(annotation: NovelAnnotation) {
        val store = library.store
        persist { store.putAnnotation(annotation) }
    }

    fun resourceVersion(point: Locator): String? =
        owned?.online?.chapterVersions?.value?.get(NovelAnchors.chapter(point.href.toString()))

    fun remove(annotation: NovelAnnotation) =
        save(annotation.copy(deleted = true, modifiedAt = System.currentTimeMillis()))

    fun edit(annotation: NovelAnnotation?) {
        _editing.value = annotation
    }

    fun beginSearch() {
        val session = owned ?: return
        searching = true
        if (indexJob?.isActive == true) return
        val request = generation
        val indexing = ++indexRevision
        indexJob = viewModelScope.launch {
            var scope: it.lorelore.nyanime.novel.NovelSearchScope? = null
            try {
                queryJob?.cancel()
                searchBook = null
                _hits.value = emptyList()
                val prepared =
                    library.search.prepare(session) {
                        withContext(Dispatchers.Main.immediate) {
                            if (request == generation && indexing == indexRevision) {
                                _searchProgress.value = it
                            }
                        }
                    }
                scope = prepared
                if (request != generation || indexing != indexRevision) return@launch
                searchBook = prepared.book
                if (query.isNotBlank()) search(query, immediate = true)
                var lastRefresh = 0L
                library.search.index(prepared) {
                    withContext(Dispatchers.Main.immediate) {
                        if (request == generation && indexing == indexRevision) {
                            _searchProgress.value = it
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (
                                searching &&
                                query.isNotBlank() &&
                                (now - lastRefresh >= 250 || it.completed == it.total)
                            ) {
                                lastRefresh = now
                                search(query, immediate = true)
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _errors.tryEmit(error)
            } finally {
                withContext(Dispatchers.IO + NonCancellable) { scope?.close() }
                if (request == generation && indexing == indexRevision) {
                    _searchProgress.value = _searchProgress.value?.copy(active = false)
                }
            }
        }
    }

    fun endSearch() {
        searching = false
        indexRevision++
        indexJob?.cancel()
        queryJob?.cancel()
        searchBook = null
        _searchProgress.value = _searchProgress.value?.copy(active = false)
    }

    fun search(value: String, immediate: Boolean = false) {
        query = value
        queryJob?.cancel()
        val book = searchBook ?: return
        val request = generation
        queryJob = viewModelScope.launch {
            if (!immediate) delay(250)
            try {
                val result = library.search.find(book, value)
                if (request == generation && value == query && searchBook === book) {
                    _hits.value = result
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _errors.tryEmit(error)
            }
        }
    }

    private fun persist(operation: suspend () -> Unit) = library.commit(operation)

    private fun closeCurrent() {
        checkpoint()
        annotationsJob?.cancel()
        chapterVersionsJob?.cancel()
        endSearch()
        owned?.let { library.release(it, indexJob) }
        owned = null
        pageReady = false
        _locator.value = null
        _annotations.value = emptyList()
        _chapterVersions.value = emptyMap()
        _hits.value = emptyList()
        _searchProgress.value = null
        _editing.value = null
        _state.value = NovelReaderState.Preparing()
    }

    override fun onCleared() {
        generation++
        closeCurrent()
        onClosed(
            listOfNotNull(
                loadJob,
                annotationsJob,
                chapterVersionsJob,
                indexJob,
                queryJob,
                positionJob,
            ),
        )
        super.onCleared()
    }
}
