package eu.kanade.tachiyomi.ui.explore

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val PLAYER_LOAD_TIMEOUT_MS = 15_000L
internal val ExploreTrailerControlsHeight = 48.dp

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ExploreYoutubePlayer(
    id: String,
    viewportHeight: Dp,
    active: Boolean,
    modifier: Modifier,
    onUnavailable: (unsupported: Boolean) -> Unit,
) {
    var played by remember(id) { mutableStateOf(false) }
    var muted by remember(id) { mutableStateOf(true) }
    var player by remember(id) { mutableStateOf<WebView?>(null) }
    val unavailable by rememberUpdatedState(onUnavailable)
    val activeNow by rememberUpdatedState(active)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    LaunchedEffect(id, active) {
        if (!active) return@LaunchedEffect
        delay(PLAYER_LOAD_TIMEOUT_MS)
        if (!played) unavailable(false)
    }
    fun createPlayer(context: Context): WebView {
        val appId = context.packageName.lowercase()
        val origin = "https://$appId"
        return WebView(context).apply {
            player = this
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.sourceId().startsWith("$origin/")) {
                        when (message.message()) {
                            "nyanime-trailer-playing" -> {
                                played = true
                                return true
                            }
                            "nyanime-trailer-unavailable" -> {
                                unavailable(false)
                                return true
                            }
                            "nyanime-trailer-unsupported" -> {
                                unavailable(true)
                                return true
                            }
                        }
                    }
                    return super.onConsoleMessage(message)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) unavailable(false)
                }
            }
            loadDataWithBaseURL(
                "$origin/",
                """
                <!doctype html><html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="referrer" content="strict-origin-when-cross-origin">
                <style>
                html,body{margin:0;background:transparent;overflow:hidden}
                iframe{position:fixed;top:0;left:0;display:block;width:100%;
                height:${viewportHeight.value.roundToInt()}px;border:0;visibility:hidden}
                </style>
                </head><body>
                <iframe id="trailer" title="YouTube" allow="autoplay; encrypted-media; picture-in-picture"
                  src="https://www.youtube-nocookie.com/embed/$id?enablejsapi=1&amp;playsinline=1&amp;origin=$origin&amp;controls=0"></iframe>
                <script>
                function onYouTubeIframeAPIReady() {
                  window.explorePlayer = new YT.Player('trailer', {events:{
                    onReady: e => {
                      e.target.getIframe().style.visibility = "visible";
                      requestAnimationFrame(() => { e.target.mute(); e.target.playVideo(); });
                    },
                    onStateChange: e => {
                      if (e.data === YT.PlayerState.PLAYING) console.log("nyanime-trailer-playing");
                      if (e.data === YT.PlayerState.ENDED) { e.target.seekTo(0, true); e.target.playVideo(); }
                    },
                    onError: e => console.log([100, 101, 150].includes(e.data)
                      ? "nyanime-trailer-unsupported" : "nyanime-trailer-unavailable")
                  }});
                }
                </script>
                <script src="https://www.youtube.com/iframe_api"
                  onerror="console.log('nyanime-trailer-unavailable')"></script>
                </body></html>
                """.trimIndent(),
                "text/html",
                "UTF-8",
                null,
            )
        }
    }

    fun releasePlayer(frame: FrameLayout) {
        val view = frame.getChildAt(0) as? WebView ?: return
        frame.removeView(view)
        view.evaluateJavascript("window.explorePlayer?.mute(); window.explorePlayer?.pauseVideo();", null)
        view.onPause()
        view.destroy()
        if (player === view) {
            player = null
            played = false
            muted = true
        }
    }

    Column(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(viewportHeight),
            factory = ::FrameLayout,
            update = { frame ->
                if (active != (frame.childCount != 0)) {
                    // Keep the Compose node attached while swapping the native child after layout.
                    mainHandler.post {
                        if (!activeNow) {
                            releasePlayer(frame)
                        } else if (frame.isAttachedToWindow && frame.childCount == 0) {
                            frame.addView(createPlayer(frame.context), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                        }
                    }
                }
            },
            onRelease = { frame -> mainHandler.post { releasePlayer(frame) } },
        )
        Row(
            Modifier.fillMaxWidth().height(ExploreTrailerControlsHeight)
                .background(MaterialTheme.colorScheme.background).padding(end = 12.dp)
                .alpha(if (active) 1f else 0f),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(
                enabled = active && played,
                onClick = {
                    val command = if (muted) "unMute" else "mute"
                    player?.evaluateJavascript("window.explorePlayer?.$command();", null)
                    muted = !muted
                },
            ) {
                Icon(
                    if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    stringResource(if (muted) R.string.explore_unmute_trailer else R.string.explore_mute_trailer),
                )
            }
        }
    }
}
