package eu.kanade.tachiyomi.data.releases

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/** Bound to one service's account pages. No bridge, password inspection or automatic submission. */
internal object AnimeScheduleBrowserGuide {
    const val LOGIN = "https://animeschedule.net/login"
    private val apiPath = Regex("/users/[A-Za-z0-9_~-]+/settings/api(?:/create)?/?")
    private val json = Json { ignoreUnknownKeys = true }

    fun ownPage(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host == "animeschedule.net" && uri.port == -1 && uri.userInfo == null
    }.getOrDefault(false)

    fun apiTarget(path: String?): String? = path?.takeIf { apiPath.matches(it) }?.let {
        ("https://animeschedule.net$it").takeIf(::ownPage)
    }

    fun tokenPage(url: String?): Boolean = ownPage(url) &&
        runCatching {
            Regex("/users/[A-Za-z0-9_~-]+/settings/api/?").matches(URI(url!!).rawPath)
        }.getOrDefault(false)

    fun state(result: String?): GuideState? = runCatching {
        json.decodeFromString<GuideState>(result ?: return null)
    }.getOrNull()

    fun token(result: String?): String? = runCatching {
        json.decodeFromString<String>(result ?: return null).trim()
            .takeIf { it.length in 16..8192 && it.none(Char::isWhitespace) }
    }.getOrNull()
}

@Serializable internal data class GuideState(
    val stage: String = "OTHER",
    val cropped: Boolean = false,
    val api: String = "",
    val target: String = "",
    val tokenAvailable: Boolean = false,
)
