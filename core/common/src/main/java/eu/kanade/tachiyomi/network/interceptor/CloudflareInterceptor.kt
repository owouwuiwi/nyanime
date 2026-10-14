package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Adapted from Mihon v0.21.2 (Apache-2.0); host ABI and cancellable single recovery retained.
class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    private val coordinator = HostChallengeCoordinator()

    private val listenerScript = """
        addEventListener("message", ({data}) => {
            if (data?.source === "cloudflare-challenge") {
                nyanime?.postMessage(data.event);
            }
        })
    """.trimIndent()

    override fun shouldIntercept(response: Response): Boolean {
        // Check if Cloudflare anti-bot is on
        // Checking the cf-mitigated header is the official way to detect a Cloudflare challenge:
        // https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/
        return response.header("cf-mitigated") == "challenge" && response.header("Server") in SERVER_CHECK
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        coordinator.acquire(request.url.host).use { lease ->
            val (result, generation) = lease.request({ chain.call().isCanceled() }) {
                val previousClearance = clearance(request.url)
                chain.proceed(request) to previousClearance
            }
            val (response, previousClearance) = result
            if (!shouldIntercept(response) ||
                request.body?.isOneShot() == true ||
                request.body?.isDuplex() == true
            ) {
                return response
            }
            response.close()
            lease.recover(
                generation = generation,
                cancelled = { chain.call().isCanceled() },
                cleared = { isCleared(request.url, previousClearance) },
            ) {
                if (!prepareWebView()) {
                    throw IOException(context.stringResource(MR.strings.information_webview_required))
                }
                solve(chain, request, previousClearance)
            }
            return chain.proceed(request)
        }
    }

    override fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response {
        response.close()
        solve(chain, request, clearance(request.url))
        return chain.proceed(request)
    }

    private fun clearance(url: HttpUrl): String? {
        return cookieManager.get(url).firstOrNull { it.name == "cf_clearance" }?.value
    }

    private fun isCleared(url: HttpUrl, previousClearance: String?): Boolean {
        val clearance = clearance(url)
        return !clearance.isNullOrBlank() && clearance != previousClearance
    }

    private fun solve(chain: Interceptor.Chain, request: Request, clearance: String?) {
        try {
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            resolveWithWebView(chain, request, clearance)
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            throw IOException(context.stringResource(MR.strings.information_cloudflare_bypass_failure), e)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(chain: Interceptor.Chain, originalRequest: Request, originalClearance: String?) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webview: WebView? = null
        var listenerScriptHandler: ScriptHandler? = null
        val useIsolatedWorld = WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)

        val challengeFound = AtomicBoolean(false)
        val cloudflareBypassed = AtomicBoolean(false)
        val challengeFailed = AtomicBoolean(false)
        val isWebViewOutdated = AtomicBoolean(false)
        val isRenderProcessGone = AtomicBoolean(false)

        val disposed = AtomicBoolean(false)
        val origRequestUrl = originalRequest.url.toString()
        // A WebView can only load a page with GET, and loading the url of another method usually reaches an
        // endpoint that answers GET without a challenge to solve. The clearance covers the whole site, so it's
        // earned on the site's root page instead.
        val challengeUrl = if (originalRequest.method == "GET") {
            origRequestUrl
        } else {
            originalRequest.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        }
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            if (disposed.get()) return@execute
            try {
                webview = createWebView(originalRequest)

                fun onChallengeEvent(event: String) {
                    if (disposed.get()) return
                    when (event) {
                        // The challenge cannot be solved non-interactively, abort.
                        "interactiveBegin" -> latch.countDown()
                        "fail" -> {
                            challengeFailed.set(true)
                            latch.countDown()
                        }
                    }
                }

                if (useIsolatedWorld) {
                    // An isolated world keeps the bridge out of the page's reach
                    val world = WebViewCompat.getExecutionWorld(webview, "nyanime")
                    val origins = setOf(originalRequest.url.origin())
                    WebViewCompat.addWebMessageListener(webview, "nyanime", origins, world) {
                            _,
                            message,
                            _,
                            isMainFrame,
                            _,
                        ->
                        if (isMainFrame) message.data?.let(::onChallengeEvent)
                    }
                    listenerScriptHandler = WebViewCompat.addJavaScriptOnEvent(
                        webview,
                        listenerScript,
                        WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                        origins,
                        world,
                    )
                } else {
                    webview.addJavascriptInterface(
                        object {
                            @Suppress("unused")
                            @JavascriptInterface
                            fun postMessage(event: String) = onChallengeEvent(event)
                        },
                        "nyanime",
                    )
                }

                webview.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (disposed.get() || isRenderProcessGone.get()) return
                        if (!challengeFailed.get() && isCleared(originalRequest.url, originalClearance)) {
                            cloudflareBypassed.set(true)
                            latch.countDown()
                        }

                        if (url == challengeUrl) {
                            if (!challengeFound.get()) {
                                // The first request didn't return the challenge, abort.
                                latch.countDown()
                            } else if (!useIsolatedWorld) {
                                view.evaluateJavascript(listenerScript, null)
                            }
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            if (errorResponse?.responseHeaders["cf-mitigated"] == "challenge") {
                                // Found the Cloudflare challenge page.
                                challengeFound.set(true)
                            } else {
                                // Unlock thread, the challenge wasn't found.
                                latch.countDown()
                            }
                        }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        // Not handling it kills the app, failing the bypass is enough
                        logcat(LogPriority.ERROR) {
                            "WebView renderer gone during Cloudflare bypass: crashed=${detail.didCrash()}, " +
                                "priority=${detail.rendererPriorityAtExit()}"
                        }
                        isRenderProcessGone.set(true)
                        latch.countDown()
                        return true
                    }
                }

                webview.loadUrl(challengeUrl, headers)
            } catch (error: Exception) {
                challengeFailed.set(true)
                logcat(LogPriority.WARN, error) { "Could not initialize the verification browser" }
                latch.countDown()
            }
        }

        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                if (chain.call().isCanceled()) throw InterruptedIOException("Request cancelled")
                if (System.nanoTime() >= deadline) break
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Request interrupted").apply { initCause(error) }
        } finally {
            disposed.set(true)

            executor.execute {
                // Only destroy can be called on a WebView whose renderer is gone
                if (!cloudflareBypassed.get() && !isRenderProcessGone.get()) {
                    isWebViewOutdated.set(webview?.isOutdated() == true)
                }

                if (isWebViewOutdated.get()) {
                    context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
                }
                webview?.run {
                    try {
                        if (!isRenderProcessGone.get()) {
                            if (useIsolatedWorld) {
                                WebViewCompat.removeWebMessageListener(
                                    this,
                                    WebViewCompat.getExecutionWorld(this, "nyanime"),
                                    "nyanime",
                                )
                            } else {
                                removeJavascriptInterface("nyanime")
                            }
                            listenerScriptHandler?.remove()
                            stopLoading()
                        }
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "Could not detach verification browser listeners" }
                    } finally {
                        destroy()
                    }
                }
            }
        }

        // Throw exception if we failed to bypass Cloudflare
        if (!cloudflareBypassed.get()) {
            // A clearance from a failed challenge would only be rejected again
            cookieManager.remove(originalRequest.url, COOKIE_NAMES, 0)

            throw CloudflareBypassException()
        }
    }
}

// An origin rule without a port only matches the scheme's default one
private fun HttpUrl.origin(): String {
    return newBuilder().username("").password("").encodedPath("/")
        .query(null).fragment(null).build().toString().removeSuffix("/")
}

private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()
