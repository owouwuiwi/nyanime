package eu.kanade.tachiyomi.data.share

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

@Serializable
enum class SharedMedium { ANIME, MANGA, TV }

/** Source references are opaque extension data, never database IDs or playable stream URLs. */
@Serializable
data class ContentLink(
    val version: Int = 1,
    val medium: SharedMedium,
    val sourceId: Long,
    val entryUrl: String,
    val title: String,
    val sourceName: String? = null,
    val itemUrl: String? = null,
    val itemTitle: String? = null,
    val positionMs: Long? = null,
    /** Human page number: page one is always 1, independently of reading direction. */
    val page: Int? = null,
) {
    fun entryOnly(): ContentLink = copy(itemUrl = null, itemTitle = null, positionMs = null, page = null)

    fun itemFromStart(): ContentLink = copy(
        positionMs = if (medium == SharedMedium.ANIME && itemUrl != null) 0 else null,
        page = if (medium == SharedMedium.MANGA && itemUrl != null) 1 else null,
    )
}

object ContentLinks {
    /** Kept for links sent by earlier versions. */
    const val PREFIX = "nyanime://open/v1#"
    const val WEB_PREFIX = "https://owouwuiwi.github.io/open/#"
    const val MAX_LINK_LENGTH = 24_000
    const val MAX_POSITION_MS = 30L * 24 * 60 * 60 * 1000
    const val MAX_PAGE = 100_000
    private val json = Json { ignoreUnknownKeys = true }
    private val linkInText = Regex(
        "(?:nyanime://open/|https://(?:owouwuiwi|noire342)\\.github\\.io/open/)[^\\s<>\"]+",
        RegexOption.IGNORE_CASE,
    )
    private val encoded = Regex("[A-Za-z0-9_-]+")

    fun encode(link: ContentLink): String {
        require(valid(link)) { "Invalid content reference" }
        val fields = linkedMapOf(
            "source" to link.sourceId.toString(),
            "ref" to link.entryUrl,
            "title" to link.title,
        )
        link.sourceName?.let { fields["sourceName"] = it }
        link.itemUrl?.let { fields["item"] = it }
        link.itemTitle?.let { fields["itemTitle"] = it }
        link.positionMs?.let { fields["at"] = it.toString() }
        link.page?.let { fields["page"] = it.toString() }
        val slug = Normalizer.normalize(link.title, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-').take(80).trimEnd('-').ifEmpty { "title" }
        val result = WEB_PREFIX +
            "v2/${link.medium.name.lowercase(Locale.ROOT)}/$slug?" +
            fields.entries.joinToString("&") { (key, value) ->
                // Slashes are readable but remain opaque data inside the fragment.
                val encodedValue = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                    .let { if (key == "ref" || key == "item") it.replace("%2F", "/") else it }
                "$key=$encodedValue"
            }
        require(result.length <= MAX_LINK_LENGTH)
        return result
    }

    /** Also accepts our share message sent back through Android's Share with Nyanime action. */
    fun extract(text: String): String? {
        if (text.length > MAX_LINK_LENGTH + 4096) return null
        val matches = linkInText.findAll(text).take(2).toList()
        return matches.singleOrNull()?.value?.takeIf { it.length <= MAX_LINK_LENGTH }
    }

    fun decode(text: String): ContentLink? = runCatching {
        val value = extract(text) ?: return null
        val uri = URI(value)
        if (!isContentUri(uri) ||
            uri.rawQuery != null ||
            uri.rawUserInfo != null ||
            uri.port != -1
        ) {
            return null
        }
        val fragment = uri.rawFragment ?: return null
        if (uri.scheme.equals("https", true) || uri.rawPath == "/v2") {
            return decodeReadable(fragment)?.takeIf(::valid)
        }
        if (uri.rawPath != "/v1") return null
        if (!encoded.matches(fragment)) return null
        val bytes = Base64.getUrlDecoder().decode(fragment)
        val utf8 = strictUtf8(bytes)
        json.decodeFromString<ContentLink>(utf8).takeIf(::valid)
    }.getOrNull()

    /** Recognizes the route even when its payload is invalid so it can show the link error. */
    fun isContentUri(text: String): Boolean = runCatching { isContentUri(URI(text)) }.getOrDefault(false)

    private fun isContentUri(uri: URI): Boolean =
        (uri.scheme.equals("nyanime", true) && uri.host.equals("open", true)) ||
            (
                uri.scheme.equals("https", true) &&
                    (uri.host.equals("owouwuiwi.github.io", true) || uri.host.equals("noire342.github.io", true)) &&
                    uri.rawPath == "/open/"
                )

    private fun decodeReadable(fragment: String): ContentLink? {
        val parts = fragment.split('?', limit = 2)
        if (parts.size != 2) return null
        val route = parts[0].split('/')
        if (route.size != 3 ||
            route[0] != "v2" ||
            !Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(route[2]) ||
            route[2].length > 80
        ) {
            return null
        }
        val medium = when (route[1]) {
            "anime" -> SharedMedium.ANIME
            "manga" -> SharedMedium.MANGA
            "tv" -> SharedMedium.TV
            else -> return null
        }
        val fields = linkedMapOf<String, String>()
        val pairs = parts[1].split('&')
        if (pairs.size > 20) return null
        for (pair in pairs) {
            val field = pair.split('=', limit = 2)
            if (field.size != 2) return null
            val key = decodePart(field[0])
            if (fields.put(key, decodePart(field[1])) != null) return null
        }
        fun optionalLong(key: String): Long? = fields[key]?.let { it.toLongOrNull() ?: error("Invalid $key") }
        return ContentLink(
            medium = medium,
            sourceId = fields["source"]?.toLongOrNull() ?: return null,
            entryUrl = fields["ref"] ?: return null,
            title = fields["title"] ?: return null,
            sourceName = fields["sourceName"],
            itemUrl = fields["item"],
            itemTitle = fields["itemTitle"],
            positionMs = optionalLong("at"),
            page = fields["page"]?.let { it.toIntOrNull() ?: return null },
        )
    }

    private fun decodePart(value: String): String {
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char == '%') {
                require(index + 1 < value.length)
                bytes.write(value.substring(index, index + 2).toInt(16))
                index += 2
            } else {
                require(char.code in 33..126)
                bytes.write(if (char == '+') 32 else char.code)
            }
        }
        return strictUtf8(bytes.toByteArray())
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun valid(link: ContentLink): Boolean = with(link) {
        fun clean(value: String, max: Int): Boolean = value.isNotBlank() &&
            value.length <= max &&
            value.none { it.code < 32 || it.code == 127 }
        (
            medium != SharedMedium.TV ||
                (
                    nyanime.tv.api.TvRules.valid(nyanime.tv.api.TvChannelRef(sourceId, entryUrl)) &&
                        itemUrl == null &&
                        itemTitle == null &&
                        positionMs == null &&
                        page == null
                    )
            ) &&
            version == 1 &&
            sourceId != 0L &&
            clean(entryUrl, 4096) &&
            clean(title, 512) &&
            (sourceName == null || clean(sourceName, 128)) &&
            (itemUrl == null || clean(itemUrl, 4096)) &&
            (itemTitle == null || clean(itemTitle, 512)) &&
            (itemUrl != null || (itemTitle == null && positionMs == null && page == null)) &&
            (positionMs == null || (medium == SharedMedium.ANIME && positionMs in 0..MAX_POSITION_MS)) &&
            (page == null || (medium == SharedMedium.MANGA && page in 1..MAX_PAGE))
    }
}
