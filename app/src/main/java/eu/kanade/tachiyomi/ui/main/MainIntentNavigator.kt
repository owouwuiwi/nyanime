package eu.kanade.tachiyomi.ui.main

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.more.settings.screen.browse.AnimeExtensionStoresScreen
import eu.kanade.presentation.more.settings.screen.browse.ExtensionCatalogueScreen
import eu.kanade.presentation.more.settings.screen.browse.MangaExtensionReposScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.tachiyomi.core.common.Constants
import eu.kanade.tachiyomi.data.backup.BackupFileFormat
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.releases.ReleaseAgendaWidget
import eu.kanade.tachiyomi.data.share.ContentLinks
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.GlobalMangaSearchScreen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreenType
import eu.kanade.tachiyomi.ui.deeplink.anime.DeepLinkAnimeScreen
import eu.kanade.tachiyomi.ui.deeplink.content.SharedContentScreen
import eu.kanade.tachiyomi.ui.deeplink.manga.DeepLinkMangaScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity.Companion.INTENT_ANIMESEARCH
import eu.kanade.tachiyomi.ui.main.MainActivity.Companion.INTENT_SEARCH
import eu.kanade.tachiyomi.ui.main.MainActivity.Companion.INTENT_SEARCH_FILTER
import eu.kanade.tachiyomi.ui.main.MainActivity.Companion.INTENT_SEARCH_QUERY
import eu.kanade.tachiyomi.ui.main.MainActivity.Companion.INTENT_SEARCH_TYPE
import eu.kanade.tachiyomi.ui.player.session.PlaybackPresentation
import eu.kanade.tachiyomi.ui.player.session.PlaybackSessionService
import eu.kanade.tachiyomi.ui.player.session.PlaybackSessions
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Translates Android intents into navigation actions for the current Voyager host. */
internal class MainIntentNavigator(
    private val applicationContext: Context,
    private val scope: CoroutineScope,
    private val onReady: () -> Unit,
) {
    fun navigate(intent: Intent, navigator: Navigator) {
        if (intent.action == PlaybackSessionService.OPEN ||
            intent.action == PlaybackSessionService.OPEN_START
        ) {
            val startPresentation = if (intent.action ==
                PlaybackSessionService.OPEN_START
            ) {
                Injekt.get<PlayerPreferences>()
                    .startPresentation().get().presentation
            } else {
                PlaybackPresentation.Page
            }
            scope.launch {
                withTimeoutOrNull(15_000) {
                    PlaybackSessions.current.filterNotNull().first()
                }?.present(startPresentation)
            }
            return
        }
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(
                applicationContext,
                notificationId,
                intent.getIntExtra("groupId", 0),
            )
        }

        val tabToOpen = when (intent.action) {
            Constants.SHORTCUT_ANIMELIB -> HomeScreen.Tab.AnimeLib()
            Constants.SHORTCUT_LIBRARY -> HomeScreen.Tab.Library()
            Constants.SHORTCUT_MANGA -> {
                val idToOpen = intent.extras?.getLong(Constants.MANGA_EXTRA) ?: return
                navigator.popUntilRoot()
                HomeScreen.Tab.Library(idToOpen)
            }
            Constants.SHORTCUT_ANIME -> {
                val idToOpen = intent.extras?.getLong(Constants.ANIME_EXTRA) ?: return
                navigator.popUntilRoot()
                HomeScreen.Tab.AnimeLib(idToOpen)
            }
            ReleaseAgendaWidget.OPEN -> HomeScreen.Tab.Releases
            Constants.SHORTCUT_UPDATES -> HomeScreen.Tab.Updates
            Constants.SHORTCUT_HISTORY -> HomeScreen.Tab.History
            Constants.SHORTCUT_SOURCES -> HomeScreen.Tab.Browse(false)
            Constants.SHORTCUT_ANIMEEXTENSIONS -> HomeScreen.Tab.Browse(true, true)
            Constants.SHORTCUT_EXTENSIONS -> HomeScreen.Tab.Browse(true)
            Constants.SHORTCUT_DOWNLOADS -> {
                navigator.popUntilRoot()
                HomeScreen.Tab.More(toDownloads = true)
            }
            Constants.SHORTCUT_ANIME_DOWNLOADS -> {
                navigator.popUntilRoot()
                HomeScreen.Tab.More(toDownloads = true)
            }
            Intent.ACTION_SEARCH, Intent.ACTION_SEND, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                // If the intent match the "standard" Android search intent
                // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

                // Get the search query provided in extras, and if not null, perform a global search with it.
                val query = intent.getStringExtra(SearchManager.QUERY)
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)

                if (!query.isNullOrEmpty()) {
                    navigator.popUntilRoot()

                    val contentLink = ContentLinks.extract(query)
                        ?: query.takeIf { it.contains("nyanime://open/", ignoreCase = true) }
                    if (contentLink != null) {
                        navigator.push(SharedContentScreen(contentLink))
                    } else {
                        val screenType = intent.getStringExtra(INTENT_SEARCH_TYPE).orEmpty()
                            .ifBlank { "ANIME" }
                            .let(DeepLinkScreenType::valueOf)

                        when (screenType) {
                            DeepLinkScreenType.MANGA -> {
                                navigator.push(GlobalMangaSearchScreen(query))
                                navigator.push(DeepLinkMangaScreen(query))
                            }
                            DeepLinkScreenType.ANIME -> {
                                navigator.push(GlobalAnimeSearchScreen(query))
                                navigator.push(DeepLinkAnimeScreen(query))
                            }
                        }
                    }
                }
                null
            }
            INTENT_SEARCH -> { // Used by extensions (url intent handlers)
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                if (!query.isNullOrEmpty()) {
                    val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                    navigator.popUntilRoot()
                    navigator.push(GlobalMangaSearchScreen(query, filter))
                }
                null
            }
            INTENT_ANIMESEARCH -> { // Same as above
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                if (!query.isNullOrEmpty()) {
                    val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                    navigator.popUntilRoot()
                    navigator.push(GlobalAnimeSearchScreen(query, filter))
                }
                null
            }
            Intent.ACTION_VIEW -> {
                // Versioned content links always resolve through an installed extension.
                if (ContentLinks.isContentUri(intent.data.toString())) {
                    navigator.popUntilRoot()
                    navigator.push(SharedContentScreen(intent.data.toString()))
                }
                // Handling opening of backup files
                else if (BackupFileFormat.acceptsPath(intent.data?.path)) {
                    navigator.popUntilRoot()
                    navigator.push(RestoreBackupScreen(intent.data.toString()))
                } else if (intent.scheme == "nyanime" && intent.data?.host == "extension-catalogue") {
                    intent.data?.getQueryParameter("url")?.let { repoUrl ->
                        navigator.popUntilRoot()
                        navigator.push(ExtensionCatalogueScreen(repoUrl.take(4096)))
                    }
                }
                // Deep link to add anime extension repo
                else if (intent.isAddAnimeExtensionStoreIntent()) {
                    intent.data?.getQueryParameter("url")?.let { repoUrl ->
                        navigator.popUntilRoot()
                        navigator.push(AnimeExtensionStoresScreen(repoUrl))
                    }
                } // Deep link to add extension repo
                else if ((intent.scheme == "tachiyomi" && intent.data?.host == "add-repo") ||
                    (intent.scheme == "nyanime" && intent.data?.host == "manga-repo")
                ) {
                    intent.data?.getQueryParameter("url")?.let { repoUrl ->
                        navigator.popUntilRoot()
                        navigator.push(MangaExtensionReposScreen(repoUrl))
                    }
                }
                null
            }
            else -> return
        }

        if (tabToOpen != null) {
            scope.launch { HomeScreen.openTab(tabToOpen) }
        }

        onReady()
    }

    private fun Intent.isAddAnimeExtensionStoreIntent(): Boolean {
        return (scheme == "aniyomi" && (data?.host == "add-repo" || data?.host == "extension-store")) ||
            (scheme == "nyanime" && data?.host == "anime-store")
    }
}
