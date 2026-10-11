package nyanime.secretgame

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.File

internal enum class GameFailure { GRAPHICS, RESTORE, SAVE }

internal data class GameUiState(
    val loading: Boolean = true,
    val paused: Boolean = true,
    val material: Int = 2,
    val brush: Int = 2,
    val failure: GameFailure? = null,
)

/** The host has no dependency on a particular simulator's JavaScript or WASM exports. */
internal interface SecretGameEngine {
    val state: StateFlow<GameUiState>
    fun attach(view: WebView)
    fun command(type: String, value: Int? = null)
    fun foreground(active: Boolean)
    suspend fun checkpoint(): Boolean
    fun startFresh()
    fun close()
}

internal class SandspielEngine(context: Context) : SecretGameEngine {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(GameUiState())
    override val state: StateFlow<GameUiState> = mutableState
    private val store = SceneStore(File(app.filesDir, "secret-game/sandspiel.scene"))
    private val writes = Mutex()
    private val assembler = SnapshotAssembler()
    private var view: WebView? = null
    private var released = false
    private var ready = false
    private var active = true
    private var closingReceipt: CompletableDeferred<Boolean>? = null
    private var checkpointRequest = 0L

    fun unavailable(error: Throwable) {
        Log.e("SecretGame", "Unable to create the offline game WebView", error)
        mutableState.update { it.copy(loading = false, failure = GameFailure.GRAPHICS) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun attach(view: WebView) {
        check(this.view == null && !released)
        this.view = view
        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)
        ) {
            mutableState.update { it.copy(loading = false, failure = GameFailure.GRAPHICS) }
            return
        }
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
        }
        val files = mapOf(
            "index.html" to "text/html",
            "game.js" to "text/javascript",
            "game.css" to "text/css",
            "sandtable_bg.wasm" to "application/wasm",
        )
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/secret-game/") { path ->
                val mime = files[path] ?: return@addPathHandler denied()
                WebResourceResponse(
                    mime,
                    if (mime ==
                        "application/wasm"
                    ) {
                        null
                    } else {
                        "UTF-8"
                    },
                    app.assets.open(ASSETS + path),
                )
            }
            .build()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                assets.shouldInterceptRequest(request.url) ?: denied()

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                ready = false
                mutableState.update { it.copy(loading = false, failure = GameFailure.GRAPHICS) }
                (view.parent as? ViewGroup)?.removeView(view)
                if (this@SandspielEngine.view === view) this@SandspielEngine.view = null
                view.destroy()
                return true
            }
        }
        WebViewCompat.addWebMessageListener(view, "SecretGameHost", setOf(ORIGIN)) { _, message, origin, mainFrame, _ ->
            if (released || !mainFrame || origin.toString() != ORIGIN) return@addWebMessageListener
            val data = message.data ?: return@addWebMessageListener
            if (data.length > 24_576) return@addWebMessageListener
            try {
                receive(Json.parseToJsonElement(data).jsonObject)
            } catch (_: Exception) {
                assembler.reset()
                closingReceipt?.complete(false)
            }
        }
        view.loadUrl(ENTRY)
        scope.launch {
            delay(15_000)
            if (!released && mutableState.value.loading) {
                mutableState.update { it.copy(loading = false, failure = GameFailure.GRAPHICS) }
            }
        }
    }

    private fun receive(event: JsonObject) {
        if (event["v"]?.jsonPrimitive?.intOrNull != 1) return
        when (event["type"]?.jsonPrimitive?.content) {
            "ready" -> {
                ready = true
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { runCatching { store.read() } }
                    if (released) return@launch
                    saved.fold(
                        onSuccess = { payload ->
                            if (payload == null) {
                                post("init")
                            } else {
                                post("restoreBegin")
                                payload.chunked(24_000).forEachIndexed { index, part ->
                                    post("restorePart", "index" to JsonPrimitive(index), "data" to JsonPrimitive(part))
                                }
                                post("restoreEnd")
                            }
                        },
                        onFailure = { mutableState.update { it.copy(loading = false, failure = GameFailure.RESTORE) } },
                    )
                    if (!active) post("suspend")
                }
            }
            "initialized" -> mutableState.update { it.copy(loading = false) }
            "state" -> {
                val material = event["material"]?.jsonPrimitive?.intOrNull ?: return
                val brush = event["brush"]?.jsonPrimitive?.intOrNull ?: return
                val paused = event["paused"]?.jsonPrimitive?.booleanOrNull ?: return
                if (material !in MATERIAL_IDS || brush !in 0..4) return
                mutableState.update { it.copy(material = material, brush = brush, paused = paused) }
            }
            "snapshotBegin" -> assembler.begin(
                event.getValue("id").jsonPrimitive.longOrNull ?: return,
                event.getValue("partCount").jsonPrimitive.intOrNull ?: return,
            )
            "snapshotPart" -> assembler.append(
                event.getValue("id").jsonPrimitive.longOrNull ?: return,
                event.getValue("index").jsonPrimitive.intOrNull ?: return,
                event.getValue("data").jsonPrimitive.content,
            )
            "snapshotEnd" -> {
                val payload = assembler.finish(event.getValue("id").jsonPrimitive.longOrNull ?: return)
                val receipt = closingReceipt.takeIf {
                    event["requestId"]?.jsonPrimitive?.longOrNull == checkpointRequest &&
                        checkpointRequest > 0
                }
                scope.launch {
                    val saved = writes.withLock { withContext(Dispatchers.IO) { runCatching { store.write(payload) } } }
                    if (saved.isFailure) {
                        mutableState.update { it.copy(failure = GameFailure.SAVE) }
                    } else if (mutableState.value.failure ==
                        GameFailure.SAVE
                    ) {
                        mutableState.update { it.copy(failure = null) }
                    }
                    receipt?.complete(saved.isSuccess)
                }
            }
            "error" -> {
                val stage = event["stage"]?.jsonPrimitive?.content.orEmpty().take(24)
                val reason = event["reason"]?.jsonPrimitive?.content.orEmpty().take(240)
                Log.w("SecretGame", "Offline game failed at $stage: $reason")
                mutableState.update {
                    it.copy(
                        loading = false,
                        failure = if (event["code"]?.jsonPrimitive?.content ==
                            "SCENE_INVALID"
                        ) {
                            GameFailure.RESTORE
                        } else {
                            GameFailure.GRAPHICS
                        },
                    )
                }
            }
        }
    }

    private fun post(type: String, vararg values: Pair<String, JsonPrimitive>) {
        val target = view ?: return
        if (released || !ready) return
        val payload = JsonObject(mapOf("v" to JsonPrimitive(1), "type" to JsonPrimitive(type), *values))
        WebViewCompat.postWebMessage(target, WebMessageCompat(payload.toString()), Uri.parse(ORIGIN))
    }

    override fun command(type: String, value: Int?) {
        if (mutableState.value.loading ||
            mutableState.value.failure in setOf(GameFailure.GRAPHICS, GameFailure.RESTORE)
        ) {
            return
        }
        if (type !in setOf("pause", "resume", "clear", "undo", "material", "brush")) return
        if (value == null) post(type) else post(type, "value" to JsonPrimitive(value))
    }

    override fun foreground(active: Boolean) {
        this.active = active
        if (active) {
            view?.onResume()
            post("foreground")
        } else {
            post("suspend")
            view?.onPause()
        }
    }

    override suspend fun checkpoint(): Boolean {
        if (released ||
            !ready ||
            mutableState.value.loading ||
            mutableState.value.failure in setOf(GameFailure.RESTORE, GameFailure.GRAPHICS)
        ) {
            return true
        }
        val receipt = CompletableDeferred<Boolean>()
        closingReceipt = receipt
        checkpointRequest++
        post("checkpoint", "requestId" to JsonPrimitive(checkpointRequest))
        return try {
            withTimeoutOrNull(1_000) { receipt.await() } ?: false
        } finally {
            if (closingReceipt === receipt) closingReceipt = null
        }
    }

    override fun startFresh() {
        if (mutableState.value.failure != GameFailure.RESTORE) return
        mutableState.update { it.copy(failure = null, loading = true) }
        post("init")
        post("clear")
        post("checkpoint")
    }

    override fun close() {
        if (released) return
        post("dispose")
        released = true
        closingReceipt?.complete(false)
        val target = view
        view = null
        target?.stopLoading()
        (target?.parent as? ViewGroup)?.removeView(target)
        target?.destroy()
        scope.cancel()
    }

    companion object {
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val ENTRY = "$ORIGIN/secret-game/index.html"
        private const val ASSETS = "secret-game/sandspiel/"
        private val MATERIAL_IDS = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14, 15, 16, 17, 18, 19)
        private fun denied() = WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}
