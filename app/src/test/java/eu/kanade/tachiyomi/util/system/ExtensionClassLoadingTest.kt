package eu.kanade.tachiyomi.util.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionClassLoadingTest {
    @Test fun `bundled suspend and network ABI types use host identity`() {
        listOf(
            "kotlin.coroutines.Continuation",
            "kotlin.jvm.functions.Function1",
            "kotlinx.coroutines.CoroutineScope",
            "eu.kanade.tachiyomi.animesource.AnimeSource",
            "eu.kanade.tachiyomi.animesource.model.AnimeFilterList",
            "eu.kanade.tachiyomi.source.MangaSource",
            "eu.kanade.tachiyomi.source.MangaCatalogIdResolver",
            "rx.Observable",
            "okhttp3.Response",
            "okio.BufferedSource",
            "org.jsoup.nodes.Document",
            "nyanime.tv.api.TvSource",
            "nyanime.news.api.NewsSource",
            "nyanime.extension.api.SourceCapabilitiesFactory",
        ).forEach { assertTrue(ExtensionClassLoading.hostFirst(it), it) }
    }

    @Test fun `source implementations and private dependency versions remain isolated`() {
        listOf(
            "eu.kanade.tachiyomi.animeextension.example.Source",
            "eu.kanade.tachiyomi.extension.example.Source",
            "kotlinx.serialization.json.Json",
            "example.privatehelper.Utility",
            "androidx.compose.runtime.Composer",
            "androidx.lifecycle.compose.FlowExtKt",
            "androidx.lifecycle.viewmodel.compose.ViewModelKt",
            "androidx.activity.compose.BackHandlerKt",
            "kotlinish.Continuation",
        ).forEach { assertFalse(ExtensionClassLoading.hostFirst(it), it) }
        assertFalse(ExtensionClassLoading.hostFirst(null))
    }

    @Test fun `Android view ABI is shared only for RIN addons without changing source loaders`() {
        listOf(
            "nyanime.addon.api.RinAddon",
            "androidx.lifecycle.LifecycleOwner",
            "androidx.activity.ComponentActivity",
            "androidx.core.view.ViewCompat",
        ).forEach {
            assertTrue(ExtensionClassLoading.hostFirst(it, addon = true), it)
            assertFalse(ExtensionClassLoading.hostFirst(it), it)
        }
        listOf(
            "androidx.compose.runtime.Composer",
            "androidx.lifecycle.compose.FlowExtKt",
            "androidx.activity.compose.BackHandlerKt",
        ).forEach {
            assertFalse(ExtensionClassLoading.hostFirst(it, addon = true), it)
        }
    }
}
