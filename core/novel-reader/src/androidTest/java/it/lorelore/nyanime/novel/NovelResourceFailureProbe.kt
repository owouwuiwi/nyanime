@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package it.lorelore.nyanime.novel

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Isolated synthetic failures: never opens the installed app's databases or source registry. */
internal object NovelResourceFailureProbe {
    private suspend fun <T> Resource.withResource(block: suspend (Resource) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }
    private class Fixture(
        context: Context,
        label: String,
        images: Boolean = false,
        val load: suspend Fixture.(String) -> NovelOnlineFile,
    ) : AutoCloseable {
        val origin = NovelOrigin(183, "resource-failure-$label")
        val chapter = NovelOnlineChapter("chapter", "Synthetic chapter")
        val imageHref = NovelOnlineIdentity.resourceHref("timed-image")
        val bytes =
            NovelOnlineHtml.prepare(
                chapter,
                NovelOnlineBody(
                    "<p>Resource failure fixture</p>" +
                        if (images) "<img src='declared'>" else "",
                    true,
                    if (images) mapOf("declared" to "timed-image") else emptyMap(),
                ),
                listOf(chapter),
            )
                .bytes
        val file =
            File(context.cacheDir, "resource-failure-$label.xhtml").apply { writeBytes(bytes) }
        val leases = AtomicInteger()
        val closes = AtomicInteger()
        val book =
            NovelBook(
                NovelIdentity.remote(origin.sourceId, origin.reference),
                "Synthetic resource failure",
                format = NovelFormat.ONLINE,
                contentVersion = NovelOnlineIdentity.edition(origin),
                origin = origin,
                importedAt = 0,
                modifiedAt = 0,
            )
        val publication =
            NovelOnlinePublication.open(
                book,
                object : NovelOnlineAccess {
                    override val document =
                        NovelOnlineDocument(
                            bookId = book.id,
                            origin = origin,
                            chapters = listOf(chapter),
                            updatedAt = 0,
                        )
                    override val chapterVersions = MutableStateFlow(emptyMap<String, String>())

                    override fun contains(href: String) =
                        href == chapter.href || (images && href == imageHref)

                    override fun retain(href: String): AutoCloseable {
                        leases.incrementAndGet()
                        return AutoCloseable { check(leases.decrementAndGet() >= 0) }
                    }

                    override suspend fun materialize(href: String) = load(this@Fixture, href)

                    override fun close() {
                        closes.incrementAndGet()
                    }
                },
            )

        fun ready() = NovelOnlineFile(file, MediaType.HTML, NovelOnlineIdentity.digest(bytes))

        fun resource() = requireNotNull(publication.get(requireNotNull(Url(chapter.href))))

        override fun close() {
            publication.close()
            publication.close()
            check(leases.get() == 0 && closes.get() == 1)
            check(!file.exists() || file.delete())
        }
    }

    fun run(instrumentation: Instrumentation, context: Context, scenario: String) {
        check(context.packageName == "it.lorelore.nyanime.novel.test")
        val result = Bundle()
        var outcome = Activity.RESULT_OK
        try {
            val passed =
                if (scenario == "resource-failures") {
                    runBlocking { resourceFailures(context) }
                } else {
                    require(scenario == "web-timeout" || scenario == "web-close")
                    webFailure(instrumentation, context, scenario == "web-close")
                }
            result.putString("passed", passed.joinToString(","))
        } catch (failure: Throwable) {
            outcome = Activity.RESULT_CANCELED
            result.putString("failure", failure.stackTraceToString())
        }
        instrumentation.finish(outcome, result)
    }

    private suspend fun resourceFailures(context: Context): List<String> {
        Fixture(context, "timeout") { withTimeout(20) { awaitCancellation() } }
            .use { fixture ->
                fixture.resource().withResource { resource ->
                    check(resource.properties().getOrNull() == null)
                    check(resource.length().getOrNull() == null)
                    check(resource.read().getOrNull() == null)
                    check(currentCoroutineContext().isActive)
                }
            }
        Fixture(context, "cancelled-source") {
            throw CancellationException("Synthetic source closed")
        }
            .use { fixture ->
                fixture.resource().withResource { check(it.read().getOrNull() == null) }
                check(currentCoroutineContext().isActive)
            }
        val stopped = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        Fixture(context, "close") {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
            .use { fixture ->
                coroutineScope {
                    val resource = fixture.resource()
                    val read = async { resource.read() }
                    started.await()
                    fixture.publication.close()
                    check(read.await().getOrNull() == null && !read.isCancelled)
                    stopped.await()
                    resource.close()
                }
            }
        val callerStarted = CompletableDeferred<Unit>()
        val callerStopped = CompletableDeferred<Unit>()
        val returned = AtomicBoolean()
        Fixture(context, "caller-cancel") {
            callerStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                callerStopped.complete(Unit)
            }
        }
            .use { fixture ->
                coroutineScope {
                    val resource = fixture.resource()
                    val read = async {
                        resource.read()
                        returned.set(true)
                    }
                    callerStarted.await()
                    read.cancelAndJoin()
                    check(read.isCancelled && !returned.get())
                    fixture.publication.close()
                    callerStopped.await()
                    resource.close()
                }
            }
        var attempt = 0
        Fixture(context, "retry") {
            if (++attempt == 1) withTimeout(20) { awaitCancellation() }
            ready()
        }
            .use { fixture ->
                fixture.resource().withResource { resource ->
                    check(resource.read().getOrNull() == null)
                    check(resource.read().getOrNull()?.contentEquals(fixture.bytes) == true)
                }
                check(attempt == 2)
            }
        return listOf(
            "internal_timeout_is_a_read_error",
            "source_cancellation_is_a_read_error",
            "close_pending_read_is_a_read_error",
            "caller_cancellation_is_preserved",
            "retry_after_internal_timeout_keeps_publication",
        )
    }

    private fun firstWeb(view: View): WebView? {
        if (view is WebView && view.url != "about:blank") return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                firstWeb(view.getChildAt(index))?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun webFailure(
        instrumentation: Instrumentation,
        context: Context,
        closePending: Boolean,
    ): List<String> {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var activity: Activity? = null
        Fixture(context, if (closePending) "web-close" else "web-timeout", images = true) { href ->
            if (href == imageHref) {
                started.countDown()
                try {
                    if (closePending) {
                        awaitCancellation()
                    } else {
                        withTimeout(50) { awaitCancellation() }
                    }
                } finally {
                    stopped.countDown()
                }
            }
            ready()
        }
            .use { fixture ->
                try {
                    NovelProbeSession.publication = fixture.publication
                    NovelProbeSession.loaded = CountDownLatch(1)
                    activity =
                        instrumentation.startActivitySync(
                            Intent(context, NovelProbeActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    check(started.await(15, TimeUnit.SECONDS)) {
                        "WebView never requested the synthetic image"
                    }
                    if (!closePending) {
                        check(NovelProbeSession.loaded.await(15, TimeUnit.SECONDS)) {
                            "Navigator failed after an image timeout"
                        }
                        check(stopped.await(2, TimeUnit.SECONDS))
                        val evaluated = CountDownLatch(1)
                        var body: String? = null
                        instrumentation.runOnMainSync {
                            requireNotNull(firstWeb(requireNotNull(activity).window.decorView))
                                .evaluateJavascript("document.body.innerText") {
                                    body = it
                                    evaluated.countDown()
                                }
                        }
                        check(
                            evaluated.await(5, TimeUnit.SECONDS) &&
                                body?.contains("Resource failure fixture") == true,
                        )
                    }
                } finally {
                    activity?.let { owner -> instrumentation.runOnMainSync { owner.finish() } }
                    instrumentation.waitForIdleSync()
                    fixture.publication.close()
                    NovelProbeSession.publication = null
                }
                check(stopped.await(2, TimeUnit.SECONDS))
                // Give Chromium's actual background callbacks time to finish after activity
                // destruction.
                SystemClock.sleep(1_000)
            }
        return listOf(
            if (closePending) {
                "webview_close_with_pending_read_has_no_uncaught_cancellation"
            } else {
                "webview_image_timeout_preserves_readable_text_without_jni_crash"
            },
        )
    }
}
