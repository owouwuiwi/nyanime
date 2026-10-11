package eu.kanade.presentation.more.settings.screen

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.browse.AnimeExtensionStoresScreen
import eu.kanade.presentation.more.settings.screen.browse.MangaExtensionReposScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.search.SmartTitleSearch
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import mihon.domain.extension.anime.interactor.GetAnimeExtensionStoreCountAsFlow
import mihon.domain.extensionrepo.manga.interactor.GetMangaExtensionRepoCount
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsBrowseScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow

        val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
        val getMangaExtensionRepoCount = remember { Injekt.get<GetMangaExtensionRepoCount>() }
        val getExtensionStoreCountAsFlow = remember { Injekt.get<GetAnimeExtensionStoreCountAsFlow>() }

        val mangaReposCount by getMangaExtensionRepoCount.subscribe().collectAsState(0)
        val animeReposCount by getExtensionStoreCountAsFlow().collectAsState(0)

        return listOf(
            Preference.PreferenceGroup(
                title = context.getString(R.string.search_smart_settings),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.rememberAtlasSearch(),
                        title = context.getString(R.string.atlas_remember),
                        subtitle = context.getString(R.string.atlas_remember_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.atlasKeyboardOnOpen(),
                        title = context.getString(R.string.atlas_keyboard),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.liveAtlasSearch(),
                        title = context.getString(R.string.atlas_live),
                        subtitle = context.getString(R.string.atlas_live_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.tolerantSearch(),
                        title = context.getString(R.string.search_tolerant),
                        subtitle = context.getString(R.string.search_tolerant_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.onlineSearchAssistance(),
                        title = context.getString(R.string.search_catalog_assistance),
                        subtitle = context.getString(R.string.search_catalog_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = context.getString(R.string.search_clear_cache),
                        subtitle = context.getString(R.string.search_clear_cache_summary),
                        onClick = {
                            scope.launch {
                                Injekt.get<SmartTitleSearch>().clear()
                                Toast.makeText(context, R.string.search_cache_cleared, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideInAnimeLibraryItems(),
                        title = stringResource(AYMR.strings.pref_hide_in_anime_library_items),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideInMangaLibraryItems(),
                        title = stringResource(AYMR.strings.pref_hide_in_manga_library_items),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(AYMR.strings.label_anime_extension_repos),
                        subtitle = pluralStringResource(
                            MR.plurals.num_repos,
                            animeReposCount.toInt(),
                            animeReposCount,
                        ),
                        onClick = {
                            navigator.push(AnimeExtensionStoresScreen())
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(AYMR.strings.label_manga_extension_repos),
                        subtitle = pluralStringResource(
                            MR.plurals.num_repos,
                            mangaReposCount,
                            mangaReposCount,
                        ),
                        onClick = {
                            navigator.push(MangaExtensionReposScreen())
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_nsfw_content),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.showNsfwSource(),
                        title = stringResource(MR.strings.pref_show_nsfw_source),
                        subtitle = stringResource(MR.strings.requires_app_restart),
                        onValueChanged = {
                            (context as FragmentActivity).authenticate(
                                title = context.stringResource(MR.strings.pref_category_nsfw_content),
                            )
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        stringResource(MR.strings.parental_controls_info),
                    ),
                ),
            ),
        )
    }
}
