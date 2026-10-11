package eu.kanade.tachiyomi.ui.main

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cast.CastController
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadCache
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadCache
import eu.kanade.tachiyomi.data.extensionstore.CatalogueUpgradeReset
import eu.kanade.tachiyomi.data.player.service.HttpServerService
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.player.ExternalIntents
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.session.PlaybackHost
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.isNavigationBarNeedsScrim
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import it.lorelore.nyanime.attention.AttentionHost
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import mihon.core.migration.Migrator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class MainActivity : BaseActivity() {
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (eu.kanade.tachiyomi.ui.player.session.PlaybackActivityBridge.key(this, keyCode, event)) return true
        val controller = CastController.get(this)
        if (controller.state.value.active &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            controller.adjustVolume(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05f else -0.05f)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val preferences: BasePreferences by injectLazy()

    private val animeDownloadCache: AnimeDownloadCache by injectLazy()
    private val downloadCache: MangaDownloadCache by injectLazy()
    private val chapterCache: ChapterCache by injectLazy()

    private val getAnimeIncognitoState: GetAnimeIncognitoState by injectLazy()
    private val getMangaIncognitoState: GetMangaIncognitoState by injectLazy()

    // To be checked by splash screen. If true then splash screen will be removed.
    var ready = false

    private var navigator: Navigator? = null
    private var launchIntentHandled = false
    private val intentNavigator by lazy {
        MainIntentNavigator(applicationContext, lifecycleScope, onReady = { ready = true })
    }

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedState: Bundle?) {
        // Removed optional workspaces may occur in an older saved Voyager stack.
        // Discard only that Activity snapshot; library, preferences and progress stay on disk.
        val savedInstanceState = savedState?.takeIf {
            NavigationRestorePolicy.canRestore(it.getInt(NavigationRestorePolicy.KEY, 0))
        }
        if (eu.kanade.tachiyomi.data.migration.MigrationState.deferredStartup(this)) {
            super.onCreate(savedInstanceState)
            startActivity(Intent(this, eu.kanade.tachiyomi.data.migration.MigrationActivity::class.java))
            finish()
            // A process started during replacement has no graph; its next launch must initialize afresh.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 500)
            return
        }
        val isLaunch = savedInstanceState == null
        launchIntentHandled = savedInstanceState?.getBoolean("nyanime_launch_intent_handled") ?: false
        if (!launchIntentHandled) {
            @Suppress("DEPRECATION")
            savedInstanceState?.getParcelable<Intent>("nyanime_pending_launch_intent")?.let(::setIntent)
        }

        // Prevent splash screen showing up on configuration changes
        val splashScreen = if (isLaunch) installSplashScreen() else null

        super.onCreate(savedInstanceState)

        val didMigration = Migrator.awaitAndRelease()
        // Migration also runs on a clean install. Completing the welcome must not turn
        // that first launch into an update notice, including after restoring a backup.
        val wasConfigured = preferences.shownOnboardingFlow().get()

        // Explicit returns can reorder Main above another activity. Rotations and explicit returns
        // must restore that screen even when it no longer occupies the task's root.
        if (
            savedInstanceState == null &&
            !isTaskRoot &&
            intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        ) {
            finish()
            return
        }

        setComposeContent {
            val catalogueReset = remember { CatalogueUpgradeReset(Injekt.get()) }
            MainStartup(
                catalogueReset = catalogueReset,
                onReady = { ready = true },
                onExit = ::finishAndRemoveTask,
            ) {
                MainNavigation(
                    preferences = preferences,
                    mangaIndexing = downloadCache.isInitializing,
                    animeIndexing = animeDownloadCache.isInitializing,
                    mangaIncognito = getMangaIncognitoState,
                    animeIncognito = getAnimeIncognitoState,
                    needsNavigationScrim = this::isNavigationBarNeedsScrim,
                    onSystemBarColors = ::applySystemBarColors,
                    onNavigatorReady = { navigator -> attachNavigator(navigator, isLaunch) },
                    navigatorEffects = { navigator ->
                        HandleNewIntents(navigator)
                        MainUpdateChecks(navigator)
                    },
                    overlays = {
                        PlaybackHost(this)
                        AttentionHost(this)
                    },
                )
                MainStartupNotices(this, lifecycleScope, wasConfigured, didMigration)
            }
        }

        val startTime = System.currentTimeMillis()
        splashScreen?.setKeepOnScreenCondition {
            val elapsed = System.currentTimeMillis() - startTime
            elapsed <= SPLASH_MIN_DURATION || !ready && elapsed <= SPLASH_MAX_DURATION
        }
        setSplashScreenExitAnimation(splashScreen)

        if (isLaunch && libraryPreferences.autoClearItemCache().get()) {
            lifecycleScope.launchIO {
                chapterCache.clear()
            }
        }

        externalPlayerResult = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                val animeId = savedInstanceState?.getLong(SAVED_STATE_ANIME_KEY)
                val episodeId = savedInstanceState?.getLong(SAVED_STATE_EPISODE_KEY)

                if (animeId != null && episodeId != null) {
                    runBlocking {
                        ExternalIntents.externalIntents.initAnime(animeId, episodeId)
                    }
                }

                ExternalIntents.externalIntents.onActivityResult(result.data)
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (eu.kanade.tachiyomi.ui.player.session.PlaybackActivityBridge.keyUp(this, keyCode, event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        eu.kanade.tachiyomi.ui.player.session.PlaybackSessions.current.value?.takeIf {
            it.hostActivity === this
        }?.hostConfigurationChanged()
    }

    override fun startActivity(intent: android.content.Intent, options: android.os.Bundle?) {
        eu.kanade.tachiyomi.ui.player.session.PlaybackActivityBridge.prepareNavigation(this, intent)
        super.startActivity(intent, options)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        eu.kanade.tachiyomi.ui.player.session.PlaybackActivityBridge.leave(this)
    }

    override fun onPictureInPictureModeChanged(enabled: Boolean, configuration: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(enabled, configuration)
        eu.kanade.tachiyomi.ui.player.session.PlaybackActivityBridge.pip(this, enabled)
    }

    override fun onNewIntent(intent: Intent) {
        setIntent(intent)
        if (navigator == null) launchIntentHandled = false
        super.onNewIntent(intent)
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        when (val screen = navigator?.lastItem) {
            is AssistContentScreen -> {
                screen.onProvideAssistUrl()?.let { outContent.webUri = it.toUri() }
            }
        }
    }

    private fun attachNavigator(navigator: Navigator, isLaunch: Boolean) {
        this.navigator = navigator
        if (!launchIntentHandled) {
            intentNavigator.navigate(intent, navigator)
            launchIntentHandled = true
        }
        if (isLaunch) preferences.incognitoMode().set(false)
    }

    @Composable
    private fun HandleNewIntents(navigator: Navigator) {
        LaunchedEffect(navigator) {
            callbackFlow {
                val consumer = Consumer<Intent> { trySend(it) }
                addOnNewIntentListener(consumer)
                awaitClose { removeOnNewIntentListener(consumer) }
            }.collect { intent ->
                intentNavigator.navigate(intent, navigator)
                launchIntentHandled = true
            }
        }
    }

    private fun applySystemBarColors(
        statusBar: androidx.compose.ui.graphics.Color,
        navigationBar: androidx.compose.ui.graphics.Color,
    ) {
        val lightStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
        val darkStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(
            statusBarStyle = if (statusBar.luminance() > 0.5) lightStyle else darkStyle,
            navigationBarStyle = if (navigationBar.luminance() > 0.5) lightStyle else darkStyle,
        )
    }

    /**
     * Sets custom splash screen exit animation on devices prior to Android 12.
     *
     * When custom animation is used, status and navigation bar color will be set to transparent and will be restored
     * after the animation is finished.
     */
    @Suppress("Deprecation")
    private fun setSplashScreenExitAnimation(splashScreen: SplashScreen?) {
        val root = findViewById<View>(android.R.id.content)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && splashScreen != null) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            splashScreen.setOnExitAnimationListener { splashProvider ->
                // For some reason the SplashScreen applies (incorrect) Y translation to the iconView
                splashProvider.iconView.translationY = 0F

                val activityAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                    interpolator = LinearOutSlowInInterpolator()
                    duration = SPLASH_EXIT_ANIM_DURATION
                    addUpdateListener { va ->
                        val value = va.animatedValue as Float
                        root.translationY = value * 16.dpToPx
                    }
                }

                val splashAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                    interpolator = FastOutSlowInInterpolator()
                    duration = SPLASH_EXIT_ANIM_DURATION
                    addUpdateListener { va ->
                        val value = va.animatedValue as Float
                        splashProvider.view.alpha = value
                    }
                    doOnEnd {
                        splashProvider.remove()
                    }
                }

                activityAnim.start()
                splashAnim.start()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(NavigationRestorePolicy.KEY, NavigationRestorePolicy.SCHEMA)
        outState.putBoolean("nyanime_launch_intent_handled", launchIntentHandled)
        if (!launchIntentHandled) outState.putParcelable("nyanime_pending_launch_intent", intent)
        super.onSaveInstanceState(outState)

        ExternalIntents.externalIntents.animeId?.let {
            outState.putLong(SAVED_STATE_ANIME_KEY, it)
        }
        ExternalIntents.externalIntents.episodeId?.let {
            outState.putLong(SAVED_STATE_EPISODE_KEY, it)
        }
    }

    companion object {
        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_ANIMESEARCH = "eu.kanade.tachiyomi.ANIMESEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"
        const val INTENT_SEARCH_TYPE = "type"

        const val SAVED_STATE_ANIME_KEY = "saved_state_anime_key"
        const val SAVED_STATE_EPISODE_KEY = "saved_state_episode_key"

        private var externalPlayerResult: ActivityResultLauncher<Intent>? = null

        suspend fun startPlayerActivity(
            context: Context,
            animeId: Long,
            episodeId: Long,
            extPlayer: Boolean,
            sourceId: Long? = null,
            video: Video? = null,
            hosterIndex: Int = -1,
            videoIndex: Int = -1,
            hosterList: List<Hoster>? = null,
            startPositionMs: Long? = null,
        ) {
            if (extPlayer) {
                val sourceId = sourceId ?: (Injekt.get<GetAnime>().await(animeId)?.source ?: -1L)
                val (success, port) = HttpServerService.start(context, sourceId)
                if (!success) {
                    withUIContext { Injekt.get<Application>().toast(AYMR.strings.http_server_start_failure) }
                    return
                }

                val video = video?.copyHttpServer(port)
                val intent = try {
                    ExternalIntents.newIntent(context, animeId, episodeId, video)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    withUIContext { Injekt.get<Application>().toast(e.message) }
                    null
                } ?: return
                startPositionMs?.takeIf { it >= 0 }?.let {
                    intent.putExtra("position", it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
                withUIContext { eu.kanade.tachiyomi.ui.player.session.PlaybackSessions.current.value?.pause() }
                externalPlayerResult?.launch(intent) ?: return
            } else {
                context.startActivity(
                    PlayerActivity.newIntent(
                        context,
                        animeId,
                        episodeId,
                        hosterList,
                        hosterIndex,
                        videoIndex,
                        startPositionMs,
                    ),
                )
            }
        }
    }
}

// Splash screen
private const val SPLASH_MIN_DURATION = 500 // ms
private const val SPLASH_MAX_DURATION = 5000 // ms
private const val SPLASH_EXIT_ANIM_DURATION = 400L // ms
