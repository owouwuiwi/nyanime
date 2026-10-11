package eu.kanade.presentation.components.releases

import android.content.ClipDescription
import android.content.ClipboardManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.AiringRefreshJob
import eu.kanade.tachiyomi.data.releases.AnimeScheduleBrowserConsent
import eu.kanade.tachiyomi.data.releases.AnimeScheduleBrowserGuide
import eu.kanade.tachiyomi.data.releases.AnimeSchedulePreferences
import eu.kanade.tachiyomi.data.releases.AnimeScheduleRepository
import eu.kanade.tachiyomi.data.releases.GuideState
import eu.kanade.tachiyomi.data.releases.ReleaseAgendaWidget
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleaseReminders
import eu.kanade.tachiyomi.data.releases.ScheduleAirType
import eu.kanade.tachiyomi.data.releases.animeScheduleFailureReason
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState

class AnimeScheduleScreen : Screen() {
    @Composable override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val uri = LocalUriHandler.current
        val scope = rememberCoroutineScope()
        val preferences = remember { AnimeSchedulePreferences() }
        val connected by preferences.connected.collectAsState()
        val enabled by preferences.enabled.collectAsState()
        val channel by preferences.preferredType.collectAsState()
        val state by preferences.state.collectAsState()
        var browsing by rememberSaveable { mutableStateOf(false) }
        // Secrets never enter saved-instance state, links, logs or backups.
        var token by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        fun changed() {
            scope.launch(Dispatchers.IO) {
                ReleaseReminders.schedule(context)
                ReleaseAgendaWidget.refresh(context)
            }
            AiringRefreshJob.enqueue(context)
        }
        Scaffold(topBar = {
            AppBar(title = "AnimeSchedule", navigateUp = { if (browsing) browsing = false else navigator.pop() })
        }) { padding ->
            if (browsing) {
                ScheduleSetupBrowser(
                    Modifier.fillMaxSize().padding(padding),
                    onDone = { browsing = false },
                    onToken = { imported ->
                        token = imported
                        error = null
                        browsing = false
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { AnimeScheduleRepository().connect(imported) }
                                token = ""
                                changed()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = context.getString(
                                    scheduleErrorResource(
                                        animeScheduleFailureReason(failure),
                                    ),
                                )
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            } else {
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                stringResource(R.string.schedule_heading),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                stringResource(R.string.schedule_description),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.schedule_languages),
                                style = MaterialTheme.typography.labelLarge,
                                color = releaseColor(ReleaseMedium.ANIME),
                            )
                        }
                    }
                    if (connected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.schedule_use),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(
                                        if (enabled) R.string.schedule_connected else R.string.schedule_paused,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(enabled, onCheckedChange = {
                                preferences.enabled.set(it)
                                changed()
                            })
                        }
                        Text(stringResource(R.string.schedule_channel), style = MaterialTheme.typography.titleMedium)
                        ScheduleAirType.entries.forEach { type ->
                            Surface(
                                onClick = {
                                    preferences.preferredType.set(type.name)
                                    changed()
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = if (channel ==
                                    type.name
                                ) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = channel == type.name, onClick = null)
                                    Text(scheduleTypeLabel(type), Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.schedule_channel_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { AnimeScheduleRepository().disconnect() }
                                changed()
                            }
                        }) { Text(stringResource(R.string.schedule_disconnect)) }
                    } else {
                        Text(stringResource(R.string.schedule_setup), style = MaterialTheme.typography.titleLarge)
                        ScheduleStep("1", stringResource(R.string.schedule_step_account))
                        ScheduleStep("2", stringResource(R.string.schedule_step_token))
                        Button(enabled = !busy, onClick = {
                            browsing = true
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.schedule_start)) }
                        ScheduleStep("3", stringResource(R.string.schedule_step_verify))
                        OutlinedTextField(
                            value = token,
                            onValueChange = {
                                token = it
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.schedule_token)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            enabled = !busy,
                        )
                        TextButton(enabled = !busy, onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            if (clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ==
                                true
                            ) {
                                token = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().take(8192)
                            }
                        }) { Text(stringResource(R.string.schedule_paste)) }
                        Button(enabled = !busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { AnimeScheduleRepository().connect(token) }
                                    token = ""
                                    changed()
                                } catch (
                                    cancelled: CancellationException,
                                ) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error =
                                        context.getString(
                                            scheduleErrorResource(
                                                animeScheduleFailureReason(failure),
                                            ),
                                        )
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text(stringResource(if (busy) R.string.schedule_verifying else R.string.schedule_verify)) }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val message =
                        error ?: state.takeIf { it.isNotEmpty() }?.let { context.getString(scheduleErrorResource(it)) }
                    if (message !=
                        null
                    ) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        stringResource(R.string.schedule_fallback),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        ReleaseAgendaWidget.pin(context)
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.schedule_widget)) }
                    Text(
                        stringResource(R.string.schedule_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        uri.openUri("https://animeschedule.net")
                    }) { Text(stringResource(R.string.schedule_credit)) }
                }
            }
        }
    }
}

@Composable private fun ScheduleStep(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                number,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Responsive original forms, restricted to the service origin. No native JS bridge or password access. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSetupBrowser(modifier: Modifier, onDone: () -> Unit, onToken: (String) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<WebView?>(null) }
    var page by remember { mutableStateOf(AnimeScheduleBrowserGuide.LOGIN) }
    var guide by remember { mutableStateOf(GuideState(stage = "LOGIN")) }
    var fullSite by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var consentConfigured by remember { mutableStateOf<Boolean?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importFailed by remember { mutableStateOf(false) }
    val latestToken by rememberUpdatedState(onToken)
    val labels = mapOf(
        "username" to stringResource(R.string.schedule_form_username),
        "login" to stringResource(R.string.schedule_form_login),
        "name" to stringResource(R.string.schedule_form_name),
        "create" to stringResource(R.string.schedule_form_create),
        "select" to stringResource(R.string.schedule_form_select),
        "register" to stringResource(R.string.schedule_form_register),
        "confirmPassword" to stringResource(R.string.schedule_form_confirm_password),
    )
    val script = remember { context.assets.open("animeschedule-setup.js").bufferedReader().use { it.readText() } }
    val installScript = remember(labels) { "$script(${Json.encodeToString(labels)})" }
    val motion = appMotionEnabled()
    val latestMotion by rememberUpdatedState(motion)
    val fontScale = LocalConfiguration.current.fontScale
    fun reveal(browser: WebView) {
        browser.animate().cancel()
        if (latestMotion &&
            browser.alpha < 1f
        ) {
            browser.animate().alpha(1f).setDuration(180).start()
        } else {
            browser.alpha = 1f
        }
        loading = false
    }
    fun update(browser: WebView, result: String?) {
        if (view !== browser || !AnimeScheduleBrowserGuide.ownPage(browser.url)) return
        val parsed = AnimeScheduleBrowserGuide.state(result) ?: return
        guide = parsed
        val target = AnimeScheduleBrowserGuide.apiTarget(parsed.target)
        if (!fullSite && target != null && target != browser.url) {
            browser.loadUrl(target)
        } else {
            reveal(browser)
        }
    }
    BackHandler {
        if (guide.stage in listOf("OTHER", "RESET", "VERIFY") && view?.canGoBack() == true) view?.goBack() else onDone()
    }
    // Read structural state only. Covers AJAX-created credentials without exporting them on load.
    LaunchedEffect(view, loading, page) {
        val browser = view ?: return@LaunchedEffect
        while (isActive && view === browser) {
            delay(700)
            if (!loading && AnimeScheduleBrowserGuide.ownPage(browser.url)) {
                val currentUrl = browser.url
                browser.evaluateJavascript("window.nyanimeScheduleSetup?.state() || null") {
                    if (currentUrl == browser.url) update(browser, it)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            view?.apply {
                animate().cancel()
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
            view = null
        }
    }
    Column(modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.fillMaxWidth().animateContentSize(tween(if (motion) ModernMotion.RESIZE_MILLIS else 0))
                    .heightIn(max = 210.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        when (guide.stage) {
                            "TOKEN" -> R.string.schedule_browser_connect_title
                            "CREATE" -> R.string.schedule_browser_token_title
                            "OTHER" -> R.string.schedule_browser_full_title
                            else -> R.string.schedule_browser_login_title
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        if (guide.stage in listOf("RESET", "VERIFY")) {
                            R.string.schedule_browser_recovery_help
                        } else if (guide.stage == "ACCOUNT") {
                            R.string.schedule_browser_register_help
                        } else if (guide.stage == "LOGIN") {
                            if (consentConfigured == false) {
                                R.string.schedule_browser_login_help
                            } else {
                                R.string.schedule_browser_login_ready_help
                            }
                        } else if (guide.stage == "CREATE") {
                            R.string.schedule_browser_create_help
                        } else if (guide.stage == "TOKEN") {
                            R.string.schedule_browser_connect_help
                        } else {
                            R.string.schedule_browser_full_help
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (guide.stage in listOf("LOGIN", "ACCOUNT")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(
                            selected = guide.stage == "LOGIN",
                            onClick = { view?.loadUrl(AnimeScheduleBrowserGuide.LOGIN) },
                            label = { Text(stringResource(R.string.schedule_form_login)) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = guide.stage == "ACCOUNT",
                            onClick = { view?.loadUrl("https://animeschedule.net/signup") },
                            label = { Text(stringResource(R.string.schedule_form_register)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        fullSite = !fullSite
                        view?.evaluateJavascript("window.nyanimeScheduleSetup?.full($fullSite) || null") { result ->
                            view?.let { update(it, result) }
                        }
                    }) {
                        Text(
                            stringResource(
                                if (fullSite) R.string.schedule_browser_guided else R.string.schedule_browser_full,
                            ),
                        )
                    }
                    IconButton(onClick = { uri.openUri(page) }) {
                        Icon(Icons.Outlined.OpenInNew, stringResource(R.string.schedule_browser_external))
                    }
                }
                if (fullSite) {
                    AnimeScheduleBrowserGuide.apiTarget(guide.api)?.let { target ->
                        TextButton(onClick = { view?.loadUrl(target) }) {
                            Text(stringResource(R.string.schedule_browser_api))
                        }
                    }
                }
                if (guide.stage in listOf("OTHER", "RESET", "VERIFY") && view?.canGoBack() == true) {
                    TextButton(onClick = { view?.goBack() }) {
                        Text(stringResource(R.string.schedule_browser_back))
                    }
                }
                if (failed) {
                    Text(stringResource(R.string.schedule_browser_error), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { view?.loadUrl(page) }) {
                        Text(stringResource(R.string.schedule_browser_retry))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = {
            WebView(context).apply {
                view = this
                alpha = 0f
                setBackgroundColor(android.graphics.Color.rgb(17, 17, 20))
                setDefaultSettings()
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.textZoom = (fontScale * 100).toInt().coerceIn(100, 200)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val url = request.url
                        if (AnimeScheduleBrowserGuide.ownPage(url.toString())) {
                            return false
                        }
                        if (url.scheme == "https") uri.openUri(url.toString())
                        return true
                    }
                    override fun onPageStarted(
                        webView: WebView,
                        url: String?,
                        icon: android.graphics.Bitmap?,
                    ) {
                        loading =
                            true
                        webView.animate().cancel()
                        webView.alpha = 0f
                        failed = false
                        if (url != null) page = url
                    }
                    override fun onPageFinished(webView: WebView, url: String?) {
                        if (view !== webView || url != webView.url) return
                        if (AnimeScheduleBrowserGuide.ownPage(webView.url)) {
                            webView.evaluateJavascript(installScript) { result ->
                                if (view !== webView || url != webView.url) return@evaluateJavascript
                                if (fullSite) {
                                    webView.evaluateJavascript("window.nyanimeScheduleSetup?.full(true) || null") {
                                        update(webView, it)
                                    }
                                } else if (AnimeScheduleBrowserGuide.state(result) == null) {
                                    // Unknown HTML/script failure must never leave the browser blank.
                                    reveal(webView)
                                } else {
                                    update(webView, result)
                                }
                            }
                        } else {
                            reveal(webView)
                        }
                    }
                    override fun onReceivedError(
                        webView: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            failed = true
                            reveal(webView)
                        }
                    }
                    override fun onReceivedHttpError(
                        webView: WebView,
                        request: WebResourceRequest,
                        response: android.webkit.WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) {
                            failed = true
                            reveal(webView)
                        }
                    }
                }
                val browser = this
                // Existing website choices are preserved. No other WebView or domain is configured here.
                scope.launch {
                    val manager = CookieManager.getInstance()
                    val existing = manager.getCookie(AnimeScheduleBrowserConsent.ORIGIN)
                    val alreadyAccepted = AnimeScheduleBrowserConsent.alreadyAccepted(existing)
                    val consent = if (alreadyAccepted) {
                        null
                    } else {
                        try {
                            withTimeoutOrNull(5_000) {
                                withContext(Dispatchers.IO) { AnimeScheduleBrowserConsent().necessaryCookie() }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (view !== browser || !scope.isActive) return@launch
                    if (consent == null) {
                        consentConfigured = alreadyAccepted
                        browser.loadUrl(page)
                    } else {
                        manager.setCookie(AnimeScheduleBrowserConsent.ORIGIN, consent) { accepted ->
                            if (scope.isActive && view === browser) {
                                consentConfigured = accepted
                                manager.flush()
                                browser.loadUrl(page)
                            }
                        }
                    }
                }
            }
        })
        if (guide.stage == "TOKEN") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    enabled = guide.tokenAvailable && !importing && !loading,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val browser = view
                        if (browser != null && AnimeScheduleBrowserGuide.tokenPage(browser.url)) {
                            importing = true
                            importFailed = false
                            val originalUrl = browser.url
                            browser.evaluateJavascript("window.nyanimeScheduleSetup?.token() || ''") { result ->
                                if (view !== browser) return@evaluateJavascript
                                importing = false
                                val token = AnimeScheduleBrowserGuide.token(result)
                                if (token != null &&
                                    originalUrl == browser.url &&
                                    AnimeScheduleBrowserGuide.tokenPage(browser.url)
                                ) {
                                    latestToken(token)
                                } else {
                                    importFailed = true
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.schedule_browser_connect)) }
                if (importFailed) {
                    Text(
                        stringResource(R.string.schedule_browser_import_error),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.schedule_browser_manual))
                }
            }
        } else {
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.schedule_browser_manual))
            }
        }
    }
}

internal fun scheduleErrorResource(reason: String): Int = when (reason) {
    "AUTH" -> R.string.schedule_error_auth
    "RATE_LIMIT" -> R.string.schedule_error_limit
    "IDENTITY" -> R.string.schedule_error_identity
    "SERVER" -> R.string.schedule_error_server
    "DATA" -> R.string.schedule_error_data
    "LOCAL" -> R.string.schedule_error_local
    else -> R.string.schedule_error_network
}
