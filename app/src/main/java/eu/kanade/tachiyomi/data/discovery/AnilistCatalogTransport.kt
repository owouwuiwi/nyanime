package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.data.track.anilist.AnilistRequestLimiter
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.discovery.CatalogServiceUnavailableException
import java.io.IOException
import java.time.Clock

/** Serializes public API calls so a confirmed outage stops all queued Home requests. */
class AnilistCatalogTransport(
    client: OkHttpClient,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val client = client.newBuilder().addInterceptor(AnilistRequestLimiter).build()
    private val gate = Mutex()
    private var unavailableAt: Long? = null

    suspend fun execute(query: String, variables: JsonObject): JsonObject = gate.withLock {
        unavailableAt?.let { started ->
            if (clock.millis() - started in 0 until OUTAGE_COOLDOWN_MILLIS) throw unavailable()
            unavailableAt = null
        }
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        client.newCall(POST("https://graphql.anilist.co", body = body.toString().toRequestBody(jsonMime)))
            .await().use { response ->
                val root = runCatching { json.parseToJsonElement(response.body.string()) as? JsonObject }.getOrNull()
                val errors = root?.objects("errors").orEmpty().mapNotNull { it.text("message") }
                // This is AniList's documented JSON outage response, not a Cloudflare challenge.
                if (response.code == 403 &&
                    errors.any {
                        it.contains("API has been temporarily disabled", ignoreCase = true)
                    }
                ) {
                    unavailableAt = clock.millis()
                    throw unavailable()
                }
                if (!response.isSuccessful) {
                    throw IOException(
                        when (response.code) {
                            403 -> "AniList ha rifiutato la richiesta (HTTP 403). Riprova più tardi."
                            429 -> "Limite richieste AniList raggiunto. Attendi prima di riprovare."
                            else -> "AniList non disponibile (HTTP ${response.code})"
                        },
                    )
                }
                val data = root?.obj("data") ?: throw IOException("Risposta AniList non valida")
                if (data.isEmpty()) throw IOException(errors.firstOrNull() ?: "AniList non disponibile")
                data
            }
    }

    private fun unavailable() = CatalogServiceUnavailableException(
        "AniList ha temporaneamente disattivato il catalogo per problemi del servizio. " +
            "Non è un errore Cloudflare. Libreria, cronologia e fonti restano disponibili. Riprova tra un minuto.",
    )

    companion object {
        const val OUTAGE_COOLDOWN_MILLIS = 60_000L
    }
}
