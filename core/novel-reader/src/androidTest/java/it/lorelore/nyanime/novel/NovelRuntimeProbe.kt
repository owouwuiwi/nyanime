@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package it.lorelore.nyanime.novel

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.StringResource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real Android URI, SQLite, Readium and WebView checks, entirely inside this test APK's storage.
 */
class NovelRuntimeProbe : Instrumentation() {
    private var scenario: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        scenario = arguments?.getString("scenario")
        start()
    }

    override fun onStart() {
        scenario?.let {
            NovelResourceFailureProbe.run(this, targetContext, it)
            return
        }
        val report = Bundle()
        val passed = mutableListOf<String>()
        var outcome = Activity.RESULT_OK
        var opened: NovelLibrary.Opened? = null
        var online: Publication? = null
        var activity: Activity? = null
        try {
            val local = requireNotNull(Url("chapter.xhtml"))
            val remote = requireNotNull(Url("https://outside.invalid/chapter.xhtml"))
            val script = requireNotNull(Url("script.js"))
            val disguised = requireNotNull(Url("code.bin"))
            val calls = mutableListOf<Url>()
            var closed = 0
            val delegate =
                object : Container<Resource> {
                    override val entries = setOf(local, remote, script, disguised)

                    override fun get(url: Url): Resource {
                        calls += url
                        return object :
                            Resource by StringResource(
                                "<html><head></head><body><script src='https://outside.invalid/code.js'></script><p>Readable</p></body></html>",
                            ) {
                            override fun close() {
                                closed++
                            }
                        }
                    }

                    override fun close() = Unit
                }
            val container =
                NovelPublications.LocalContainer(
                    delegate,
                    delegate.entries,
                    mapOf(
                        local to MediaType.XHTML,
                        disguised to requireNotNull(MediaType("application/ecmascript")),
                    ),
                )
            check(remote !in container.entries && container[remote] == null)
            check(container[requireNotNull(Url("../outside"))] == null && calls.isEmpty())
            passed += "absolute_resources_denied_before_delegate"
            check(container[script] == null && container[disguised] == null && closed == 2)
            val resource = requireNotNull(container[requireNotNull(Url("chapter.xhtml#point"))])
            val html = runBlocking {
                try {
                    resource.read().getOrElse { error(it) }.toString(Charsets.UTF_8)
                } finally {
                    resource.close()
                }
            }
            check(
                html.contains("Readable") &&
                    !html.contains("<script") &&
                    html.contains("script-src https:;"),
            )
            check(closed == 3)
            passed += "scripts_denied_and_chapters_sanitized"

            check(targetContext.packageName == "it.lorelore.nyanime.novel.test") {
                "Probe must be self-targeted"
            }
            // These databases belong only to this isolated test APK; never to the real application.
            targetContext.deleteDatabase("novel_reader_v1.db")
            targetContext.deleteDatabase("novel_search_v1.db")
            val library = NovelLibrary(targetContext)
            val fixture =
                ByteArrayOutputStream()
                    .also {
                        TextPublicationCompiler.compile(
                            StringReader("A local café, a quiet chapter.\n".repeat(200)),
                            it,
                            "Runtime fixture",
                            "runtime-fixture",
                            { n -> "Part $n" },
                        )
                    }
                    .toByteArray()
            runBlocking {
                val book =
                    library.importer.import(
                        { ByteArrayInputStream(fixture) },
                        NovelFormat.EPUB,
                        "fixture.epub",
                        partTitle = { "Part $it" },
                    )
                val duplicate =
                    library.importer.import(
                        { ByteArrayInputStream(fixture) },
                        NovelFormat.EPUB,
                        "copy.epub",
                        partTitle = { "Part $it" },
                    )
                check(book.id == duplicate.id && library.store.page().total == 1)
                opened = library.open(book.id)
                val publication = requireNotNull(opened).publication
                check(
                    publication.readingOrder.first().mediaType?.toString() ==
                        "application/xhtml+xml",
                )
                passed += "atomic_import_identity_and_bare_navigator_mime"
                val positions = publication.positions()
                check(positions.size > publication.readingOrder.size)
                val middle = requireNotNull(publication.locateProgression(.5))
                check(middle.locations.progression?.let { it > 0.1 && it < 0.9 } == true)
                passed += "compressed_chapter_positions_and_mid_chapter_seek"
                val lengths =
                    NovelEpubPolicy.inspect(library.ownedFile(requireNotNull(book.publicationFile)))
                        .readingLengths
                val largeSpine =
                    NovelPositions.create(
                        List(10_000) { publication.readingOrder.first() },
                        null,
                        lengths,
                    )
                check(largeSpine.positions().size in 10_000..16_384)
                passed += "large_spine_positions_stay_bounded"
                val href = publication.readingOrder.first().href.resolve().toString()
                val locator =
                    JSONObject().put("href", href).put("type", "application/xhtml+xml").toString()
                val position = NovelPosition(book.id, book.contentVersion, locator, 200, .25, href)
                library.store.savePosition(position)
                library.store.savePosition(position.copy(updatedAt = 199, totalProgression = .1))
                library.store.savePosition(
                    position.copy(contentVersion = "other-edition", updatedAt = 300),
                )
                check(library.store.position(book.id) == position)
                val note =
                    NovelAnnotation(
                        bookId = book.id,
                        contentVersion = book.contentVersion,
                        locatorJson = locator,
                        kind = NovelAnnotationKind.NOTE,
                        note = "Keep this note",
                        createdAt = 100,
                        modifiedAt = 100,
                    )
                library.store.putAnnotation(note)
                library.store.put(book.copy(modifiedAt = book.modifiedAt + 1))
                check(library.store.annotations(book.id).single() == note)
                passed += "sqlite_position_guards_and_notes_survive_book_updates"
                library.search.index(book, publication) { check(it.completed <= it.total) }
                check(library.search.find(book, "cafe").isNotEmpty())
                check(library.search.find(book, "%%%%").isEmpty())
                passed += "android_fts_unicode_and_literal_queries"
                val wrong = runCatching {
                    library.importer.import(
                        { ByteArrayInputStream("another file".toByteArray()) },
                        NovelFormat.TXT,
                        "wrong.txt",
                        partTitle = { "Part $it" },
                        expectedId = book.id,
                    )
                }
                    .exceptionOrNull()
                check(wrong is NovelImporter.DifferentDocumentException)
                check(
                    library.store.annotations(book.id).single() == note &&
                        library.store.page().total == 1,
                )
                passed += "wrong_relink_keeps_existing_book_and_notes"
                val bounded =
                    NovelSearch(targetContext, NovelSearch.Limits(bookBytes = 16, totalBytes = 32))
                var terminal: NovelSearch.Progress? = null
                bounded.index(book, publication) { terminal = it }
                check(terminal?.limited == true && terminal?.active == false)
                check(bounded.cacheState().textBytes <= 32)
                passed += "search_budget_stops_explicitly_without_touching_book_metadata"
                val lru =
                    NovelSearch(
                        targetContext,
                        NovelSearch.Limits(bookBytes = 16_384, totalBytes = 16_384, books = 1),
                    )
                lru.index(book, publication) {}
                val other =
                    book.copy(
                        id = NovelIdentity.local("a".repeat(64)),
                        contentVersion = "other-cache-fixture",
                    )
                lru.index(other, publication) {}
                check(lru.find(book, "cafe").isEmpty() && lru.find(other, "cafe").isNotEmpty())
                check(lru.cacheState().let { it.books == 1 && it.textBytes <= it.limitBytes })
                lru.clear()
                check(lru.cacheState().textBytes == 0L)
                check(
                    library.store.annotations(book.id).single() == note &&
                        library.store.position(book.id) == position,
                )
                passed += "lru_eviction_and_cache_clear_preserve_notes_and_positions"
                passed += NovelMetadataProbe.run(targetContext, library, book, position, note)
                passed += NovelOnlineRuntimeProbe.run(targetContext)
                passed += NovelOnlineCacheProbe.run(targetContext)
                passed += NovelOnlineSearchProbe.run(targetContext)
                passed += NovelDownloadProbe.run(targetContext)
                passed += NovelSessionProbe.run(targetContext, library)
            }
            NovelProbeSession.publication = requireNotNull(opened).publication
            NovelProbeSession.loaded = CountDownLatch(1)
            activity =
                startActivitySync(
                    Intent(targetContext, NovelProbeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            check(NovelProbeSession.loaded.await(15, TimeUnit.SECONDS)) {
                "Real Readium page did not become ready"
            }
            val evaluation = CountDownLatch(1)
            var body: String? = null
            runOnMainSync {
                val webviews = mutableListOf<WebView>()
                fun collect(view: View) {
                    if (view is WebView) webviews += view
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) collect(view.getChildAt(i))
                    }
                }
                collect(requireNotNull(activity).window.decorView)
                val view =
                    webviews.firstOrNull { it.url != "about:blank" } ?: error("No rendered WebView")
                view.evaluateJavascript(
                    "JSON.stringify({text:document.body.innerText,tap:typeof Android.onTap,drag:typeof Android.onDragStart})",
                    {
                        body = it
                        evaluation.countDown()
                    },
                )
            }
            check(evaluation.await(5, TimeUnit.SECONDS) && body?.contains("local") == true) {
                "Navigator has no readable text"
            }
            check(body?.contains("function") == true && body?.contains("undefined") == false) {
                "Trusted native touch bridge is missing: $body"
            }
            passed += "real_readium_webview_page_and_trusted_bridge"
            activity?.let { old ->
                runOnMainSync { old.finish() }
                waitForIdleSync()
            }
            online = NovelOnlineRuntimeProbe.webFixture(targetContext)
            NovelProbeSession.publication = online
            NovelProbeSession.loaded = CountDownLatch(1)
            activity =
                startActivitySync(
                    Intent(targetContext, NovelProbeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            check(NovelProbeSession.loaded.await(15, TimeUnit.SECONDS)) {
                "Online navigator did not become ready"
            }
            val onlineEvaluation = CountDownLatch(1)
            var onlineBody: String? = null
            runOnMainSync {
                fun firstWeb(view: View): WebView? {
                    if (view is WebView && view.url != "about:blank") return view
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) {
                            firstWeb(view.getChildAt(index))?.let {
                                return it
                            }
                        }
                    }
                    return null
                }
                val web = requireNotNull(firstWeb(requireNotNull(activity).window.decorView))
                web.evaluateJavascript(
                    "JSON.stringify({text:document.body.innerText,tap:typeof Android.onTap,drag:typeof Android.onDragStart,image:Array.from(document.images).some(i=>i.complete&&i.naturalWidth===1),images:Array.from(document.images).map(i=>({src:i.src,complete:i.complete,width:i.naturalWidth})),url:location.href})",
                ) {
                    onlineBody = it
                    onlineEvaluation.countDown()
                }
            }
            check(
                onlineEvaluation.await(5, TimeUnit.SECONDS) &&
                    onlineBody?.contains("online fixture") == true,
            ) {
                "Source-backed navigator has no readable text: $onlineBody"
            }
            check(
                onlineBody?.contains("function") == true &&
                    onlineBody?.contains("undefined") == false,
            )
            check(onlineBody?.contains("\\\"image\\\":true") == true) {
                "Declared source image did not render: $onlineBody; calls=${NovelOnlineRuntimeProbe.webRequests}"
            }
            passed += "real_online_readium_webview_page_and_trusted_bridge"
            report.putString("passed", passed.joinToString(","))
        } catch (error: Throwable) {
            report.putString("passed", passed.joinToString(","))
            report.putString("failure", error.stackTraceToString())
            outcome = Activity.RESULT_CANCELED
        } finally {
            activity?.let { owner ->
                runOnMainSync { owner.finish() }
                waitForIdleSync()
            }
            opened?.close()
            online?.close()
            NovelProbeSession.publication = null
        }
        finish(outcome, report)
    }
}

internal object NovelProbeSession {
    var publication: Publication? = null
    var loaded = CountDownLatch(1)
}

class NovelProbeActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
        val parent = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(parent)
        val factory =
            EpubNavigatorFactory(requireNotNull(NovelProbeSession.publication))
                .createFragmentFactory(
                    initialLocator = null,
                    paginationListener =
                    object : EpubNavigatorFragment.PaginationListener {
                        override fun onPageLoaded() {
                            NovelProbeSession.loaded.countDown()
                        }
                    },
                )
        val fragment = factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
        supportFragmentManager.beginTransaction().replace(parent.id, fragment).commitNow()
    }
}
