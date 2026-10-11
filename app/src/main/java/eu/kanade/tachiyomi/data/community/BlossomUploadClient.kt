package eu.kanade.tachiyomi.data.community

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A standard BUD-11 token, with one compatibility retry for older Base64 decoders. */
internal class BlossomUploadClient(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build(),
    private val diagnostic: (String) -> Unit = {},
) {
    suspend fun upload(
        identity: CommunityIdentity,
        bytes: ByteArray,
        host: String,
    ): String = withContext(Dispatchers.IO) {
        require(safeImage(host) && URI(host).path.orEmpty() in listOf("", "/")) { "Host immagini non valido" }
        require(bytes.size in 1..2_000_000)
        val hash = sha256(bytes).hex()
        val event = NostrEvent.create(
            identity,
            24242,
            "Upload a selected Nyanime image",
            listOf(
                listOf("t", "upload"),
                listOf("x", hash),
                listOf("server", URI(host).host.lowercase()),
                listOf("expiration", (System.currentTimeMillis() / 1000 + 120).toString()),
            ),
            at = System.currentTimeMillis() / 1000 - 5,
        )
        val json = communityJson.encodeToString(event).toByteArray()
        val encodings = listOf(Base64.getUrlEncoder().withoutPadding(), Base64.getEncoder())
        for ((index, encoder) in encodings.withIndex()) {
            val request = Request.Builder().url(host.trimEnd('/') + "/upload")
                .header("Authorization", "Nostr ${encoder.encodeToString(json)}")
                .header("X-SHA-256", hash).put(bytes.toRequestBody("image/jpeg".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                diagnostic("host=${URI(host).host} status=${response.code} compatibility=${index > 0}")
                if (!response.isSuccessful) {
                    val reason = response.header("X-Reason").orEmpty() + response.peekBody(1024).string()
                    // No retries for permissions, payments or arbitrary failures. Never log a response body or token.
                    if (index == 0 &&
                        response.code in listOf(400, 401) &&
                        reason.contains("base64", true)
                    ) {
                        return@use
                    }
                    throw BlossomUploadException(response.code, URI(host).host)
                }
                val descriptor = communityJson.parseToJsonElement(
                    response.body.byteStream().use { it.readBounded(16_384) }.decodeToString(),
                ).jsonObject
                val url = descriptor["url"]?.jsonPrimitive?.content.orEmpty()
                require(
                    descriptor["sha256"]?.jsonPrimitive?.content == hash &&
                        safeImage(url) &&
                        URI(url).path.substringAfterLast('/').substringBefore('.') == hash,
                ) { "L’host ha restituito un’immagine non verificabile. La bozza è conservata." }
                verify(url, hash)
                return@withContext url
            }
        }
        throw IOException("Autorizzazione dell’host immagini non compatibile. La bozza è conservata.")
    }

    private fun verify(initial: String, hash: String) {
        var url = initial
        repeat(4) {
            // Verification is public and carries no authorization header to a CDN or redirect target.
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val redirect = response.header("Location")?.let { URI(url).resolve(it).toString() }.orEmpty()
                    require(safeImage(redirect) && hash in URI(redirect).path) {
                        "Reindirizzamento immagine non valido"
                    }
                    url = redirect
                } else {
                    if (!response.isSuccessful) throw BlossomUploadException(response.code, URI(url).host)
                    val actual = response.body.byteStream().use { it.readBounded(2_000_000) }
                    require(sha256(actual).hex() == hash) { "L’immagine ricevuta non corrisponde a quella scelta." }
                    return
                }
            }
        }
        throw IOException("Troppi reindirizzamenti dell’immagine. La bozza è conservata.")
    }
}

internal class BlossomUploadException(val status: Int, host: String) : IOException(
    "$host · " +
        when (status) {
            400, 401 -> "Autorizzazione non accettata (HTTP $status)."
            402 -> "Questo servizio richiede un pagamento."
            403 -> "Questo servizio non consente il caricamento."
            413 -> "Questo servizio richiede un’immagine più piccola."
            415 -> "Formato immagine non accettato."
            429 -> "Troppi caricamenti: riprova tra poco."
            else -> "Servizio immagini non disponibile (HTTP $status)."
        } +
        " La bozza è conservata.",
)
