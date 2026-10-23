@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package it.lorelore.nyanime.novel.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.util.view.setComposeContent
import it.lorelore.nyanime.novel.NovelAlignment
import it.lorelore.nyanime.novel.NovelAnchors
import it.lorelore.nyanime.novel.NovelAnnotation
import it.lorelore.nyanime.novel.NovelAnnotationKind
import it.lorelore.nyanime.novel.NovelAppearance
import it.lorelore.nyanime.novel.NovelFormat
import it.lorelore.nyanime.novel.NovelImporter
import it.lorelore.nyanime.novel.NovelLibrary
import it.lorelore.nyanime.novel.NovelPublications
import it.lorelore.nyanime.novel.NovelSearch
import it.lorelore.nyanime.novel.NovelTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.nio.charset.Charset

/** Private reader host. Only document URIs enter through the separate exported Open-with bridge. */
class NovelReaderActivity : BaseActivity() {
    companion object {
        private const val BOOK = "novel_book_id"
        private const val START_AT = "novel_start_href"
        private const val PREVIEW = "novel_preview_token"

        fun intent(context: Context, bookId: String, startHref: String? = null) =
            Intent(context, NovelReaderActivity::class.java)
                .putExtra(BOOK, bookId)
                .putExtra(START_AT, startHref)

        fun importIntent(context: Context, uri: Uri) =
            Intent(context, NovelReaderActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        internal fun previewIntent(context: Context, bookId: String, token: String) =
            intent(context, bookId).putExtra(PREVIEW, token)
    }

    private val previewSession by lazy {
        intent.getStringExtra(PREVIEW)?.let(NovelReaderPreview::find)
    }
    private val library by lazy { previewSession?.library ?: Injekt.get<NovelLibrary>() }
    private var readerStarted = false
    private val model by
        viewModels<NovelReaderViewModel> {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    previewSession?.let(NovelReaderPreview::claim)
                    return NovelReaderViewModel(
                        library,
                        { Injekt.get<BasePreferences>().incognitoMode().get() },
                        { pending ->
                            previewSession?.let { NovelReaderPreview.release(it, pending) }
                        },
                    )
                        as T
                }
            }
        }
    private lateinit var pageHost: FrameLayout
    private var navigator by mutableStateOf<EpubNavigatorFragment?>(null)
    private var firstPageLoaded by mutableStateOf(false)
    private var renderError by mutableStateOf(false)
    private var chrome by mutableStateOf(true)
    private var bound: String? = null
    private var locatorJob: Job? = null
    private var pageDeadline: Job? = null
    private var restoredPoint: String? = null
    private var restoredBook: String? = null
    private var restoredVersion: String? = null
    private var relinkBook: String? = null
    private var renderGeneration = 0L
    private val documentPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startImport(uri, relinkBook)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (eu.kanade.tachiyomi.data.migration.MigrationState.deferredStartup(this)) {
            startActivity(Intent(this, eu.kanade.tachiyomi.ui.main.MainActivity::class.java))
            finish()
            return
        }
        if (
            intent.hasExtra(PREVIEW) &&
            (previewSession == null || intent.getStringExtra(BOOK) != previewSession?.bookId)
        ) {
            Toast.makeText(this, R.string.novel_preview_expired, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        readerStarted = true
        restoredPoint = savedInstanceState?.getString("novel_locator")
        restoredBook = savedInstanceState?.getString(BOOK)
        restoredVersion = savedInstanceState?.getString("novel_content_version")
        relinkBook = savedInstanceState?.getString("novel_relink_book")
        val root = FrameLayout(this)
        pageHost = FrameLayout(this).apply { id = R.id.novel_navigator_container }
        root.addView(pageHost, FrameLayout.LayoutParams(-1, -1))
        val overlay = ComposeView(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        privacyDisplayController?.registerView(pageHost, PrivacyArea.READER)
        // A restored dummy navigator must never resume before the publication is reopened.
        supportFragmentManager.findFragmentByTag("novel-navigator")?.let { restored ->
            supportFragmentManager.commitNow { remove(restored) }
        }
        overlay.setComposeContent { ReaderUi() }
        if (!model.initialized) {
            val book = restoredBook ?: intent.getStringExtra(BOOK)
            if (book != null) {
                model.load(
                    book,
                    if (savedInstanceState == null) intent.getStringExtra(START_AT) else null,
                )
            } else {
                startImport()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    model.state.collect { state ->
                        if (state is NovelReaderState.Ready) attach(state.opened)
                    }
                }
                launch {
                    model.errors.collect { error ->
                        if (error == null) return@collect
                        Toast.makeText(
                            this@NovelReaderActivity,
                            if (error is NovelReaderViewModel.InvalidPositionException) {
                                R.string.novel_position_error
                            } else {
                                R.string.novel_storage_error
                            },
                            Toast.LENGTH_LONG,
                        )
                            .show()
                        model.errorHandled(error)
                    }
                }
            }
        }
        lifecycleScope.launch {
            Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
                .keepScreenOn()
                .changes()
                .collect { pageHost.keepScreenOn = it }
        }
    }

    private fun startImport(uri: Uri? = intent.data, expectedId: String? = null) {
        if (
            uri == null ||
            uri.scheme != "content" ||
            uri.authority.orEmpty().startsWith(BuildConfig.APPLICATION_ID)
        ) {
            Toast.makeText(this, R.string.novel_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val app = applicationContext
        lifecycleScope.launch {
            try {
                val (name, type) =
                    withContext(Dispatchers.IO) {
                        val name =
                            contentResolver
                                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use {
                                    if (it.moveToFirst()) it.getString(0) else null
                                } ?: getString(R.string.novel_untitled)
                        name to contentResolver.getType(uri)
                    }
                val expected = expectedId?.let { library.store.get(it) }
                val format =
                    expected?.format
                        ?: if (type == "application/epub+zip" || name.endsWith(".epub", true)) {
                            NovelFormat.EPUB
                        } else {
                            NovelFormat.TXT
                        }
                detachNavigator()
                model.import(
                    format,
                    name,
                    expected?.textEncoding?.let { Charset.forName(it) },
                    openStream = {
                        app.contentResolver.openInputStream(uri)
                            ?: throw IOException("Document is unavailable")
                    },
                    partTitle = { app.getString(R.string.novel_part, it) },
                    expectedId = expectedId,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Toast.makeText(this@NovelReaderActivity, R.string.novel_error, Toast.LENGTH_LONG)
                    .show()
                finish()
            }
        }
    }

    private fun detachNavigator() {
        renderGeneration++
        locatorJob?.cancel()
        pageDeadline?.cancel()
        navigator?.let { fragment ->
            if (!supportFragmentManager.isStateSaved) {
                supportFragmentManager.commitNow { remove(fragment) }
            }
        }
        navigator = null
        bound = null
        firstPageLoaded = false
        renderError = false
    }

    private fun attach(session: NovelLibrary.Opened) {
        if (supportFragmentManager.isStateSaved) return
        val key = "${session.book.id}:${session.book.contentVersion}"
        if (bound == key && navigator != null) return
        val rendering = ++renderGeneration
        model.awaitingPage(session)
        locatorJob?.cancel()
        pageDeadline?.cancel()
        firstPageLoaded = false
        renderError = false
        val snapshot =
            restoredPoint
                ?.takeIf {
                    restoredBook == session.book.id &&
                        restoredVersion == session.book.contentVersion
                }
                ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        val factory =
            EpubNavigatorFactory(session.publication)
                .createFragmentFactory(
                    initialLocator = snapshot ?: model.locator.value,
                    initialPreferences =
                    preferences(
                        model.appearance.value,
                        eu.kanade.domain.ui.resolveDarkTheme(
                            Injekt.get<eu.kanade.domain.ui.ThemeSettingsRepository>()
                                .current()
                                .mode,
                            resources.configuration.uiMode and
                                Configuration.UI_MODE_NIGHT_MASK ==
                                Configuration.UI_MODE_NIGHT_YES,
                        ),
                    ),
                    listener =
                    object : EpubNavigatorFragment.Listener {
                        override fun onExternalLinkActivated(url: AbsoluteUrl) {
                            if (!url.isHttp) return
                            runCatching {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())),
                                )
                            }
                        }
                    },
                    paginationListener =
                    object : EpubNavigatorFragment.PaginationListener {
                        override fun onPageLoaded() {
                            if (rendering != renderGeneration || isFinishing || isDestroyed) {
                                return
                            }
                            firstPageLoaded = true
                            pageDeadline?.cancel()
                            model.rendered(session)
                            navigator?.currentLocator?.value?.let(model::moved)
                        }
                    },
                    configuration =
                    EpubNavigatorFragment.Configuration {
                        selectionActionModeCallback = selectionMenu
                    },
                )
        supportFragmentManager.fragmentFactory = factory
        val fragment =
            factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
                as EpubNavigatorFragment
        supportFragmentManager.commitNow {
            replace(R.id.novel_navigator_container, fragment, "novel-navigator")
        }
        navigator = fragment
        bound = key
        restoredPoint = null
        fragment.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    val width = fragment.publicationView.width.coerceAtLeast(1)
                    val x = event.point.x / width
                    val rtl =
                        fragment.overflow.value.readingProgression ==
                            org.readium.r2.navigator.preferences.ReadingProgression.RTL
                    return when {
                        x < .22f ->
                            if (rtl) fragment.goForward(false) else fragment.goBackward(false)
                        x > .78f ->
                            if (rtl) fragment.goBackward(false) else fragment.goForward(false)
                        else -> {
                            chrome = !chrome
                            true
                        }
                    }
                }
            },
        )
        locatorJob = lifecycleScope.launch { fragment.currentLocator.collect { model.moved(it) } }
        pageDeadline = lifecycleScope.launch {
            delay(15_000)
            if (!firstPageLoaded) renderError = true
        }
    }

    @Composable
    private fun ReaderUi() {
        val state by model.state.collectAsState()
        val locator by model.locator.collectAsState()
        val appearance by model.appearance.collectAsState()
        val perBook by model.perBook.collectAsState()
        val annotations by model.annotations.collectAsState()
        val chapterVersions by model.chapterVersions.collectAsState()
        val editing by model.editing.collectAsState()
        val progress by model.searchProgress.collectAsState()
        val hits by model.hits.collectAsState()
        var panel by rememberSaveable { mutableStateOf<NovelPanel?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        val session = (state as? NovelReaderState.Ready)?.opened
        val nav = navigator
        val dark = MaterialTheme.colorScheme.background.luminance() < .5f
        LaunchedEffect(appearance, dark, nav) {
            nav?.submitPreferences(preferences(appearance, dark))
            val lightPage =
                when (appearance.theme) {
                    NovelTheme.LIGHT,
                    NovelTheme.PAPER,
                    -> true
                    NovelTheme.DARK -> false
                    NovelTheme.DEVICE -> !dark
                }
            WindowCompat.getInsetsController(window, pageHost).apply {
                isAppearanceLightStatusBars = lightPage
                isAppearanceLightNavigationBars = lightPage
            }
            window.attributes =
                window.attributes.apply { screenBrightness = appearance.brightness ?: -1f }
        }
        LaunchedEffect(chrome, panel, firstPageLoaded) {
            val insets = WindowCompat.getInsetsController(window, pageHost)
            insets.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chrome || panel != null || !firstPageLoaded) {
                insets.show(WindowInsetsCompat.Type.systemBars())
            } else {
                insets.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        LaunchedEffect(panel) {
            if (panel == NovelPanel.SEARCH) model.beginSearch() else model.endSearch()
        }
        LaunchedEffect(annotations, nav, session, chapterVersions) {
            if (nav != null && session != null) {
                nav.applyDecorations(
                    annotations
                        .filter {
                            NovelAnchors.state(session.book, it, chapterVersions) ==
                                NovelAnchors.State.CURRENT &&
                                it.kind != NovelAnnotationKind.BOOKMARK
                        }
                        .mapNotNull { note ->
                            runCatching { Locator.fromJSON(JSONObject(note.locatorJson)) }
                                .getOrNull()
                                ?.let {
                                    Decoration(
                                        note.id,
                                        it,
                                        Decoration.Style.Highlight(note.color.toInt()),
                                    )
                                }
                        },
                    "nyanime-novel-notes",
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                state is NovelReaderState.Failure ->
                    NovelFailure((state as NovelReaderState.Failure).cause, ::finish) {
                        if (
                            previewSession != null ||
                            (state as NovelReaderState.Failure).cause is
                                NovelLibrary.OnlineDocumentException
                        ) {
                            (restoredBook ?: intent.getStringExtra(BOOK))?.let { model.load(it) }
                        } else {
                            documentPicker.launch(arrayOf("application/epub+zip", "text/plain"))
                        }
                    }
                state is NovelReaderState.Missing ->
                    NovelMissing(::finish) {
                        relinkBook = (state as NovelReaderState.Missing).book.id
                        documentPicker.launch(arrayOf("application/epub+zip", "text/plain"))
                    }
                !firstPageLoaded ->
                    NovelOpening(
                        (state as? NovelReaderState.Preparing)?.progress,
                        renderError,
                        ::finish,
                    ) {
                        bound = null
                        session?.let(::attach)
                    }
                session != null -> {
                    NovelReaderChrome(
                        if (previewSession == null) {
                            session.book.title
                        } else {
                            stringResource(
                                R.string.novel_preview_heading,
                                session.book.title,
                            )
                        },
                        locator?.title.orEmpty(),
                        locator?.locations?.totalProgression,
                        chrome,
                        annotations.any {
                            it.kind == NovelAnnotationKind.BOOKMARK &&
                                it.contentVersion == session.book.contentVersion &&
                                NovelAnchors.state(session.book, it, chapterVersions) ==
                                NovelAnchors.State.CURRENT &&
                                it.locatorJson == model.savedPoint()
                        },
                        ::finish,
                        { bookmark(session) },
                        { fraction ->
                            lifecycleScope.launch {
                                session.publication.locateProgression(fraction)?.let { nav?.go(it) }
                            }
                        },
                        { panel = it },
                    )
                    when (panel) {
                        NovelPanel.INDEX -> {
                            val contents = remember(session) { contents(session) }
                            NovelIndexPanel(
                                contents,
                                locator?.href?.removeFragment()?.toString(),
                                {
                                    nav?.go(it)
                                    panel = null
                                },
                                { panel = null },
                            )
                        }
                        NovelPanel.APPEARANCE ->
                            NovelAppearancePanel(
                                appearance,
                                perBook,
                                model::perBook,
                                model::previewAppearance,
                                model::appearance,
                                session.book.textEncoding,
                                { charset ->
                                    panel = null
                                    val app = applicationContext
                                    detachNavigator()
                                    model.encoding(charset) {
                                        app.getString(R.string.novel_part, it)
                                    }
                                },
                            ) {
                                model.appearance(model.appearance.value)
                                panel = null
                            }
                        NovelPanel.SEARCH ->
                            NovelSearchPanel(
                                query,
                                progress,
                                hits,
                                {
                                    query = it
                                    model.search(it)
                                },
                                { hit ->
                                    go(hit)
                                    panel = null
                                },
                                model::beginSearch,
                                { panel = null },
                                cachedOnly = session.online != null,
                            )
                        NovelPanel.NOTES ->
                            NovelNotesPanel(
                                annotations,
                                session.book.contentVersion,
                                { note ->
                                    if (note.contentVersion == session.book.contentVersion) {
                                        runCatching {
                                            Locator.fromJSON(JSONObject(note.locatorJson))
                                        }
                                            .getOrNull()
                                            ?.let { point ->
                                                val verified =
                                                    NovelAnchors.state(
                                                        session.book,
                                                        note,
                                                        chapterVersions,
                                                    ) == NovelAnchors.State.CURRENT
                                                nav?.go(
                                                    if (session.online != null && !verified) {
                                                        point.copy(
                                                            locations = Locator.Locations(),
                                                            text = Locator.Text(),
                                                        )
                                                    } else {
                                                        point
                                                    },
                                                )
                                            }
                                    }
                                    if (note.kind != NovelAnnotationKind.BOOKMARK) model.edit(note)
                                    panel = null
                                },
                                { model.remove(it) },
                                { panel = null },
                                anchorState = {
                                    NovelAnchors.state(session.book, it, chapterVersions)
                                },
                            )
                        null -> Unit
                    }
                    editing?.let { note ->
                        NovelNoteEditor(
                            note,
                            {
                                model.save(it)
                                model.edit(null)
                            },
                            { model.edit(null) },
                        )
                    }
                }
            }
        }
        BackHandler(panel != null) { panel = null }
    }

    private fun contents(session: NovelLibrary.Opened): List<NovelTocItem> {
        val result = mutableListOf<NovelTocItem>()
        val groups = session.online?.document?.chapters.orEmpty().associate { it.href to it.group }
        val pending = ArrayDeque<Pair<Link, Int>>()
        val links = session.publication.tableOfContents.ifEmpty { session.publication.readingOrder }
        links.reversed().forEach { pending.addLast(it to 0) }
        while (pending.isNotEmpty() && result.size < 10_000) {
            val (link, depth) = pending.removeLast()
            result +=
                NovelTocItem(
                    "${result.size}:${link.href}",
                    link.title.orEmpty().ifBlank {
                        getString(R.string.novel_part, result.size + 1)
                    },
                    link,
                    depth,
                    groups[link.href.resolve().removeFragment().toString()],
                )
            if (depth < 32) link.children.reversed().forEach { pending.addLast(it to depth + 1) }
        }
        return result
    }

    private fun bookmark(session: NovelLibrary.Opened) {
        val point = navigator?.currentLocator?.value ?: return
        val json = model.savedPoint() ?: return
        val resourceVersion = model.resourceVersion(point)
        if (session.online != null && resourceVersion == null) return
        val existing =
            model.annotations.value.firstOrNull {
                it.kind == NovelAnnotationKind.BOOKMARK &&
                    it.locatorJson == json &&
                    NovelAnchors.state(session.book, it, model.chapterVersions.value) ==
                    NovelAnchors.State.CURRENT
            }
        if (existing != null) {
            model.remove(existing)
        } else {
            val now = System.currentTimeMillis()
            model.save(
                NovelAnnotation(
                    bookId = session.book.id,
                    contentVersion = session.book.contentVersion,
                    locatorJson = json,
                    kind = NovelAnnotationKind.BOOKMARK,
                    chapterReference = NovelAnchors.chapter(point.href.toString()),
                    resourceVersion = resourceVersion,
                    quote = point.title.orEmpty().take(8192),
                    createdAt = now,
                    modifiedAt = now,
                ),
            )
        }
    }

    private fun go(hit: NovelSearch.Hit) {
        val href = Url(hit.href) ?: return
        navigator?.go(
            Locator(
                href,
                MediaType.XHTML,
                hit.chapter,
                text =
                Locator.Text(before = hit.before, highlight = hit.highlight, after = hit.after),
            ),
        )
    }

    private val selectionMenu =
        object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, android.R.id.copy, 0, android.R.string.copy)
                menu.add(0, 901, 10, R.string.novel_highlight)
                menu.add(0, 902, 11, R.string.novel_add_note)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onDestroyActionMode(mode: ActionMode) = Unit

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId !in setOf(901, 902, android.R.id.copy)) return false
                val nav = navigator ?: return false
                val session = (model.state.value as? NovelReaderState.Ready)?.opened ?: return false
                lifecycleScope.launch {
                    try {
                        val selected = nav.currentSelection() ?: return@launch
                        if (
                            navigator !== nav ||
                            (model.state.value as? NovelReaderState.Ready)?.opened !== session
                        ) {
                            return@launch
                        }
                        val quote = selected.locator.text.highlight.orEmpty()
                        if (item.itemId == android.R.id.copy) {
                            if (quote.isBlank() || quote.length > 65_536) {
                                Toast.makeText(
                                    this@NovelReaderActivity,
                                    R.string.novel_selection_too_long,
                                    Toast.LENGTH_LONG,
                                )
                                    .show()
                                return@launch
                            }
                            getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(
                                    android.content.ClipData.newPlainText(session.book.title, quote),
                                )
                            nav.clearSelection()
                            mode.finish()
                            return@launch
                        }
                        val selectionJson =
                            if (quote.length <= 8192) selected.locator.toJSON().toString() else null
                        if (selectionJson == null || selectionJson.length > 65536) {
                            Toast.makeText(
                                this@NovelReaderActivity,
                                R.string.novel_selection_too_long,
                                Toast.LENGTH_LONG,
                            )
                                .show()
                            return@launch
                        }
                        val now = System.currentTimeMillis()
                        val resourceVersion = model.resourceVersion(selected.locator)
                        if (session.online != null && resourceVersion == null) {
                            Toast.makeText(
                                this@NovelReaderActivity,
                                R.string.novel_selection_error,
                                Toast.LENGTH_LONG,
                            )
                                .show()
                            return@launch
                        }
                        val annotation =
                            NovelAnnotation(
                                bookId = session.book.id,
                                contentVersion = session.book.contentVersion,
                                locatorJson = selectionJson,
                                chapterReference =
                                NovelAnchors.chapter(selected.locator.href.toString()),
                                resourceVersion = resourceVersion,
                                kind =
                                if (item.itemId == 901) {
                                    NovelAnnotationKind.HIGHLIGHT
                                } else {
                                    NovelAnnotationKind.NOTE
                                },
                                quote = quote,
                                createdAt = now,
                                modifiedAt = now,
                            )
                        if (item.itemId == 901) model.save(annotation) else model.edit(annotation)
                        nav.clearSelection()
                        mode.finish()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        if (
                            navigator === nav &&
                            (model.state.value as? NovelReaderState.Ready)?.opened === session
                        ) {
                            Toast.makeText(
                                this@NovelReaderActivity,
                                R.string.novel_selection_error,
                                Toast.LENGTH_LONG,
                            )
                                .show()
                        }
                    }
                }
                return true
            }
        }

    override fun onPause() {
        if (readerStarted) {
            navigator?.currentLocator?.value?.let(model::moved)
            model.checkpoint()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!readerStarted) {
            super.onSaveInstanceState(outState)
            return
        }
        val session = (model.state.value as? NovelReaderState.Ready)?.opened
        outState.putString(BOOK, session?.book?.id ?: restoredBook)
        outState.putString("novel_content_version", session?.book?.contentVersion)
        outState.putString("novel_relink_book", relinkBook)
        outState.putString("novel_locator", model.savedPoint())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        renderGeneration++
        locatorJob?.cancel()
        pageDeadline?.cancel()
        if (isFinishing && !readerStarted) {
            previewSession?.let { NovelReaderPreview.release(it, emptyList()) }
        }
        super.onDestroy()
    }

    private fun preferences(value: NovelAppearance, dark: Boolean) =
        EpubPreferences(
            fontFamily = FontFamily(value.fontFamily),
            fontSize = value.fontSize,
            lineHeight = value.lineHeight,
            pageMargins = value.margins,
            textAlign =
            if (value.alignment == NovelAlignment.JUSTIFY) {
                TextAlign.JUSTIFY
            } else {
                TextAlign.START
            },
            theme =
            when (value.theme) {
                NovelTheme.DEVICE -> if (dark) Theme.DARK else Theme.LIGHT
                NovelTheme.LIGHT -> Theme.LIGHT
                NovelTheme.DARK -> Theme.DARK
                NovelTheme.PAPER -> Theme.SEPIA
            },
            publisherStyles = value.publisherStyles,
            scroll = value.scroll,
        )
}

@Composable
private fun NovelOpening(
    progress: NovelImporter.Progress?,
    failed: Boolean,
    onBack: () -> Unit,
    retry: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!failed) CircularProgressIndicator()
            Text(
                stringResource(
                    if (failed) {
                        R.string.novel_error
                    } else {
                        when (progress?.phase) {
                            NovelImporter.Phase.COPYING -> R.string.novel_copying
                            NovelImporter.Phase.CONVERTING -> R.string.novel_converting
                            NovelImporter.Phase.VERIFYING -> R.string.novel_verifying
                            NovelImporter.Phase.SAVING -> R.string.novel_saving
                            null -> R.string.novel_opening
                        }
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            if (progress?.bytes?.let { it > 0 } == true) {
                Text(
                    Formatter.formatShortFileSize(
                        androidx.compose.ui.platform.LocalContext.current,
                        progress.bytes,
                    ),
                )
            }
            if (failed) TextButton(retry) { Text(stringResource(R.string.novel_retry)) }
            TextButton(onBack) {
                Text(stringResource(if (failed) R.string.novel_back else R.string.novel_cancel))
            }
        }
    }
}

@Composable
private fun NovelFailure(error: Throwable, onBack: () -> Unit, onChoose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Text(
                stringResource(R.string.novel_error),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(
                    if (error is NovelLibrary.OnlineDocumentException) {
                        R.string.novel_online_unavailable
                    } else if (error is NovelImporter.DifferentDocumentException) {
                        R.string.novel_wrong_original
                    } else if (error is NovelPublications.UnsupportedDrmException) {
                        R.string.novel_drm
                    } else {
                        R.string.novel_error_detail
                    },
                ),
            )
            TextButton(onChoose) {
                Text(
                    stringResource(
                        if (error is NovelLibrary.OnlineDocumentException) {
                            R.string.novel_retry
                        } else {
                            R.string.novel_choose_file
                        },
                    ),
                )
            }
            TextButton(onBack) { Text(stringResource(R.string.novel_back)) }
        }
    }
}

@Composable
private fun NovelMissing(onBack: () -> Unit, onChoose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Text(
                stringResource(R.string.novel_missing),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.novel_missing_detail))
            TextButton(onChoose) { Text(stringResource(R.string.novel_choose_file)) }
            TextButton(onBack) { Text(stringResource(R.string.novel_back)) }
        }
    }
}
