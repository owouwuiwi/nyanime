package eu.kanade.presentation.more.settings.screen.browse

import eu.kanade.domain.extension.ExtensionCatalogueLink
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.catalogueRequest
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

internal object DriveCatalogueResolver {
    private const val MAX_FOLDER_BYTES = 1024L * 1024

    suspend fun resolve(folder: ExtensionCatalogueLink.DriveFolder, client: OkHttpClient): String {
        val index = client.newCall(catalogueRequest(folder.listingUrl)).awaitSuccess().use { response ->
            require(response.request.url.host == "drive.google.com")
            val source = response.body.source()
            require(!source.request(MAX_FOLDER_BYTES + 1))
            findIndex(source.readUtf8())
        }
        return requireNotNull(index)
    }

    internal fun findIndex(html: String): String? = Jsoup.parse(html, "https://drive.google.com")
        .select(".flip-entry a[href]")
        .asSequence()
        .filter { it.selectFirst(".flip-entry-title")?.text() == "index.json" }
        .mapNotNull {
            val link = ExtensionCatalogueLink.parse(it.absUrl("href")) as? ExtensionCatalogueLink.Index
            link?.takeIf { it.isDrive }?.url
        }
        .distinct()
        .singleOrNull()
}
