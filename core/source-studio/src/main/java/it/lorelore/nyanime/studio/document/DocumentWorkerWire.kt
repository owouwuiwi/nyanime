package it.lorelore.nyanime.studio.document

import android.os.Bundle
import android.os.ParcelFileDescriptor
import it.lorelore.nyanime.source.document.SourceDocumentDescriptor
import it.lorelore.nyanime.source.document.SourceDocumentEntry
import it.lorelore.nyanime.source.document.SourceDocumentRequest
import it.lorelore.nyanime.studio.SourceBytes
import it.lorelore.nyanime.studio.StudioDocuments
import it.lorelore.nyanime.studio.studioText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Host IPC implementation, deliberately outside the versioned source API. */
object DocumentWorkerWire {
    const val RUN = 701
    const val RESULT = 702
    const val FAILED = 703
    const val NETWORK = 704
    const val NETWORK_RESPONSE = 705
    const val NETWORK_COMPLETE = 706
    const val NETWORK_FAILED = 707
    const val MAX_APK_BYTES = 16 * 1024 * 1024
    const val MAX_METADATA_BYTES = 256 * 1024
    const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    const val MAX_TRANSFER_BYTES = 64 * 1024 * 1024

    @Suppress("DEPRECATION")
    fun descriptor(bundle: Bundle, key: String): ParcelFileDescriptor? = bundle.getParcelable(key)

    fun json(bundle: Bundle, key: String): JsonObject {
        val raw = requireNotNull(bundle.getString(key))
        require(raw.toByteArray().size <= MAX_METADATA_BYTES)
        return StudioDocuments.json.parseToJsonElement(raw).jsonObject
    }

    fun read(input: InputStream, maximum: Int): ByteArray = SourceBytes.read(input, maximum)

    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    fun descriptor(value: SourceDocumentDescriptor): JsonObject = buildJsonObject {
        put("reference", value.reference)
        put("title", value.title)
        put("mimeType", value.mimeType)
        put("size", value.size?.let(::JsonPrimitive) ?: JsonNull)
        put("sha256", value.sha256?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun descriptor(value: JsonObject) = SourceDocumentDescriptor(
        value.getValue("reference").studioText(),
        value.getValue("title").studioText(),
        value.getValue("mimeType").studioText(),
        value["size"]?.jsonPrimitive?.longOrNull,
        value["sha256"]?.takeUnless { it == JsonNull }?.studioText(),
    )

    fun entries(values: List<SourceDocumentEntry>): JsonObject = buildJsonObject {
        require(values.size <= 2000)
        put(
            "entries",
            JsonArray(
                values.map { item ->
                    buildJsonObject {
                        put("reference", item.reference)
                        put("title", item.title)
                        put("mimeType", item.mimeType)
                    }
                },
            ),
        )
    }

    fun entries(value: JsonObject): List<SourceDocumentEntry> = (value.getValue("entries") as JsonArray).map { raw ->
        raw.jsonObject.let { item ->
            SourceDocumentEntry(
                item.getValue("reference").studioText(),
                item.getValue("title").studioText(),
                item.getValue("mimeType").studioText(),
            )
        }
    }.also { require(it.size <= 2000) }

    fun request(value: SourceDocumentRequest): JsonObject = buildJsonObject {
        put("url", value.url)
        put("method", value.method)
        put("headers", JsonObject(value.headers.mapValues { JsonPrimitive(it.value) }))
    }

    fun request(value: JsonObject, body: ByteArray) = SourceDocumentRequest(
        value.getValue("url").studioText(),
        value.getValue("method").studioText(),
        value.getValue("headers").jsonObject.mapValues {
            it.value.studioText()
        },
        body,
    )
}
