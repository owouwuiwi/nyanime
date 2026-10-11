package eu.kanade.presentation.more.settings.screen.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.m3.util.htmlReadyLicenseContent
import com.mikepenz.aboutlibraries.util.withContext
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class OpenSourceLicensesScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current.applicationContext
        val catalog by produceState<LicenseCatalog>(initialValue = LicenseCatalog.Loading, context) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val bundled = listOf(
                        BundledAsset(
                            "Nyanime — copyright and attribution",
                            "https://github.com/owouwuiwi/nyanime",
                            "Apache-2.0 · third-party licenses apply separately",
                            "licenses/Nyanime-LICENSE.txt",
                            "licenses/Nyanime-NOTICE.txt",
                        ),
                        BundledAsset(
                            "FFmpeg — native media libraries",
                            "https://github.com/owouwuiwi/nyanime/tree/main/tools/native",
                            "GPL-3.0-or-later",
                            "licenses/GPL-3.0.txt",
                            "licenses/player-NOTICE.txt",
                        ),
                        BundledAsset(
                            "libmpv — native video player",
                            "https://github.com/mpv-player/mpv",
                            "GPL-2.0-or-later · combined player: GPL-3.0",
                            "licenses/GPL-2.0.txt",
                            "licenses/player-NOTICE.txt",
                        ),
                        BundledAsset(
                            "mpv-android — Android player bridge",
                            "https://github.com/aniyomiorg/aniyomi-mpv-lib",
                            "MIT",
                            "licenses/mpv-android-LICENSE.txt",
                            "licenses/player-NOTICE.txt",
                        ),
                        BundledAsset(
                            "FFmpegKit — Android media bridge",
                            "https://github.com/jmir1/ffmpeg-kit/tree/1.18",
                            "LGPL-3.0 · bundled FFmpeg: GPL-3.0-or-later",
                            "licenses/LGPL-3.0.txt",
                            "licenses/player-NOTICE.txt",
                        ),
                        BundledAsset(
                            "Anime4K — image processing shaders",
                            "https://github.com/bloc97/Anime4K",
                            "MIT",
                            "anime4k/LICENSE",
                        ),
                        BundledAsset(
                            "Cinema — audio processing",
                            "https://github.com/owouwuiwi/nyanime",
                            "License and credits included with the component",
                            "audio/cinema/LICENSE.txt",
                        ),
                        BundledAsset(
                            "SymSpellKt — adapted search correction",
                            "https://github.com/wavesonics/SymSpellKt",
                            "MIT",
                            "licenses/SymSpellKt-LICENSE.txt",
                        ),
                        BundledAsset(
                            "Transitions.dev — motion reference",
                            "https://transitions.dev",
                            "Transitions.dev License",
                            "licenses/Transitions-dev-LICENSE.txt",
                        ),
                        BundledAsset(
                            "DeepPanel — panel recognition model",
                            "https://github.com/pedrovgs/DeepPanelAndroid",
                            "Apache-2.0",
                            "deeppanel/LICENSE.txt",
                            "deeppanel/NOTICE.txt",
                        ),
                        BundledAsset(
                            "Piik — lounge and connection recovery inspiration",
                            "https://github.com/TNTcraftHIM/Piik",
                            "MIT",
                            "licenses/Piik-LICENSE.txt",
                        ),
                        BundledAsset(
                            "WebRTC — optional room voice",
                            "https://github.com/webrtc-sdk/webrtc/tree/30d5e63ae91da483e06577b5c35ee91cc5e5c3db",
                            "BSD-3-Clause and third-party licenses",
                            "licenses/WebRTC-LICENSE.txt",
                            "licenses/WebRTC-ThirdParty.md",
                        ),
                        BundledAsset(
                            "WebRTC SDKs — Android binding",
                            "https://github.com/webrtc-sdk/android",
                            "MIT",
                            "licenses/WebRTC-SDK-LICENSE.txt",
                            "licenses/WebRTC-PATENTS.txt",
                        ),
                        BundledAsset(
                            "Downpour — adapted connection tuner",
                            "https://github.com/AlirezaJavan/Downpour",
                            "Apache-2.0",
                            "licenses/Downpour-LICENSE.txt",
                            "licenses/download-NOTICE.txt",
                        ),
                        BundledAsset(
                            "Fetch — adapted speed average",
                            "https://github.com/tonyofrancis/Fetch",
                            "Apache-2.0",
                            "licenses/Fetch-LICENSE.txt",
                            "licenses/download-NOTICE.txt",
                        ),
                        BundledAsset(
                            "TDLib — official Telegram engine",
                            "https://github.com/tdlib/td",
                            "BSL-1.0",
                            "licenses/TDLib-LICENSE.txt",
                            "licenses/telegram-NOTICE.txt",
                        ),
                        BundledAsset(
                            "OpenSSL — Telegram native transport",
                            "https://github.com/openssl/openssl",
                            "Apache-2.0",
                            "licenses/OpenSSL-LICENSE.txt",
                            "licenses/telegram-NOTICE.txt",
                        ),
                    ) +
                        context.assets.open("licenses/native/catalog.json").bufferedReader().use { reader ->
                            val entries = JSONArray(reader.readText())
                            List(entries.length()) { index ->
                                val item = entries.getJSONObject(index)
                                BundledAsset(
                                    item.getString("name"),
                                    item.getString("website"),
                                    item.getString("licenseName"),
                                    item.getString("asset"),
                                )
                            }
                        }
                    val bundledLicenses = bundled.map { asset ->
                        BundledLicense(
                            asset.name,
                            asset.website,
                            asset.licenseName,
                            asset.notice?.let { notice ->
                                context.assets.open(notice).bufferedReader().use { it.readText() } + "\n\n"
                            }.orEmpty() +
                                context.assets.open(asset.asset).bufferedReader().use { it.readText() },
                        )
                    }
                    LicenseCatalog.Ready(
                        Libs.Builder().withContext(context).build().libraries.toList(),
                        bundledLicenses,
                    )
                }.getOrElse { LicenseCatalog.Failed }
            }
        }
        var query by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val filtered = remember(catalog, query) {
            (catalog as? LicenseCatalog.Ready)?.libraries?.filter { library ->
                library.name.contains(query, ignoreCase = true) ||
                    library.uniqueId.contains(query, ignoreCase = true) ||
                    library.licenses.any { it.name.contains(query, ignoreCase = true) }
            }.orEmpty()
        }
        val bundled = remember(catalog, query) {
            (catalog as? LicenseCatalog.Ready)?.bundled?.filter {
                it.name.contains(query, ignoreCase = true) || it.licenseName.contains(query, ignoreCase = true)
            }.orEmpty()
        }
        LaunchedEffect(query) { listState.scrollToItem(0) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.library_licenses),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            Column(Modifier.fillMaxSize().padding(contentPadding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text(stringResource(MR.strings.action_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                )
                when (catalog) {
                    LicenseCatalog.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    LicenseCatalog.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(MR.strings.unknown_error))
                    }
                    is LicenseCatalog.Ready -> {
                        if (filtered.isEmpty() && bundled.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(MR.strings.no_results_found))
                            }
                        } else {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(bundled, key = { "bundled:" + it.name }) { library ->
                                    TextPreferenceWidget(
                                        title = library.name,
                                        subtitle = library.licenseName,
                                        onPreferenceClick = {
                                            navigator.push(
                                                OpenSourceLibraryLicenseScreen(
                                                    name = library.name,
                                                    website = library.website,
                                                    license = android.text.TextUtils.htmlEncode(library.text)
                                                        .replace("\n", "<br>"),
                                                ),
                                            )
                                        },
                                    )
                                }
                                items(filtered, key = Library::uniqueId) { library ->
                                    val licenseNames = library.licenses.joinToString { it.name }
                                    val subtitle = listOfNotNull(
                                        library.artifactVersion?.takeIf(String::isNotBlank),
                                        licenseNames.takeIf(String::isNotBlank),
                                    ).joinToString(" · ")
                                    TextPreferenceWidget(
                                        title = library.name,
                                        subtitle = subtitle,
                                        onPreferenceClick = {
                                            navigator.push(
                                                OpenSourceLibraryLicenseScreen(
                                                    name = library.name,
                                                    website = library.website,
                                                    license = library.licenses.joinToString("<br><br>") {
                                                        it.htmlReadyLicenseContent
                                                            ?: android.text.TextUtils.htmlEncode(it.name)
                                                    },
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface LicenseCatalog {
    data object Loading : LicenseCatalog
    data object Failed : LicenseCatalog
    data class Ready(val libraries: List<Library>, val bundled: List<BundledLicense>) : LicenseCatalog
}

private data class BundledAsset(
    val name: String,
    val website: String,
    val licenseName: String,
    val asset: String,
    val notice: String? = null,
)
private data class BundledLicense(val name: String, val website: String, val licenseName: String, val text: String)
