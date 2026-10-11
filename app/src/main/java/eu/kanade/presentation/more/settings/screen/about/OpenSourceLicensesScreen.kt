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
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
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
                    val bundled = listOf("licenses/catalog.json", "licenses/native/catalog.json").flatMap { path ->
                        context.assets.open(path).bufferedReader().use { reader ->
                            val entries = JSONArray(reader.readText())
                            List(entries.length()) { index ->
                                val item = entries.getJSONObject(index)
                                BundledAsset(
                                    item.getString("name"),
                                    item.getString("website"),
                                    item.getString("licenseName"),
                                    item.getString("asset"),
                                    item.optString("notice").takeIf(String::isNotBlank),
                                )
                            }
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
                        Libs.Builder().withJson(
                            context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use {
                                it.readText()
                            },
                        ).build().libraries.toList(),
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
