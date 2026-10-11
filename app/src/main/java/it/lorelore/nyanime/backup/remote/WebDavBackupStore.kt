package it.lorelore.nyanime.backup.remote

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.StringReader
import java.nio.charset.Charset
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

internal class WebDavBackupStore(
    endpoint: String,
    username: String,
    password: String,
    private val http: OkHttpClient = remoteHttpClient(),
    allowHttpForTests: Boolean = false,
) : RemoteFileStore {
    private val root: HttpUrl
    private val authorization = Credentials.basic(username, password, Charsets.UTF_8)

    init {
        val base = endpoint.trim().toHttpUrl()
        require(base.isHttps || allowHttpForTests)
        require(base.username.isBlank() && base.password.isBlank() && base.query == null && base.fragment == null)
        require(username.length <= 512 && password.length <= 4096)
        root = base.newBuilder().apply {
            if (!base.encodedPath.endsWith('/')) addPathSegment("")
            addPathSegment("nyanime-backups-v1").addPathSegment("")
        }.build()
    }

    override suspend fun prepare() {
        http.remoteRequest(request(root).method("MKCOL", ByteArray(0).toRequestBody()).build()).use {
            if (it.code != 405) it.requireSuccess()
        }
    }

    override suspend fun list(): List<RemoteObject> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:">""" +
            "<d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"
        return http.remoteRequest(
            request(
                root,
            ).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build(),
        ).use { response ->
            response.requireSuccess()
            val bytes = response.readRemoteBytes(2 * 1024 * 1024)
            require(bytes.size <= 2 * 1024 * 1024)
            parseWebDavObjects(decodeWebDavXml(bytes, response.body.contentType()?.charset()), root)
        }
    }

    override suspend fun upload(name: String, file: File): RemoteObject {
        val url = objectUrl(name)
        http.remoteRequest(
            request(
                url,
            ).header("If-None-Match", "*").put(file.asRequestBody("application/octet-stream".toMediaType())).build(),
        ).use { if (it.code != 412) it.requireSuccess() }
        // A retry may find the immutable object already present. The caller MUST read it back before commit.
        return RemoteObject(name, name, file.length())
    }

    override suspend fun download(id: String, file: File, limit: Long) {
        http.remoteRequest(request(objectUrl(id)).get().build()).use { it.saveRemote(file, limit) }
    }

    override suspend fun delete(id: String) {
        http.remoteRequest(request(objectUrl(id)).delete().build()).use { if (it.code != 404) it.requireSuccess() }
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", authorization)
    private fun objectUrl(name: String): HttpUrl {
        require(isRemoteBackupName(name))
        return root.newBuilder().addPathSegment(name).build()
    }
}

internal fun isRemoteBackupName(value: String) = value.matches(
    Regex("nyanime-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(nyabk\\.enc|json)"),
)

internal fun parseWebDavObjects(xml: String, root: HttpUrl): List<RemoteObject> {
    require(xml.length <= 2 * 1024 * 1024)
    // XML forbids entity declarations outside a DTD. Reject DTDs before parsing on every platform.
    // Android's Harmony DOM does not implement Xerces' external-entity feature switches.
    require(!Regex("<!(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml))
    require('\u0000' !in xml)
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, enabled) ->
            try {
                setFeature(feature, enabled)
            } catch (
                _: ParserConfigurationException,
            ) { /* DTD guard and rejecting resolver remain mandatory. */ }
        }
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw IllegalArgumentException("External XML entities are forbidden") }
        setErrorHandler(object : DefaultHandler() {
            override fun error(e: SAXParseException) {
                throw e
            }
            override fun fatalError(e: SAXParseException) {
                throw e
            }
        })
    }.parse(InputSource(StringReader(xml)))
    val responses = document.getElementsByTagNameNS("DAV:", "response")
    require(responses.length <= 2001)
    return buildList {
        for (index in 0 until responses.length) {
            val item = responses.item(index) as? Element ?: continue
            val href = item.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
            val url = root.resolve(href) ?: continue
            if (url.scheme != root.scheme ||
                url.host != root.host ||
                url.port != root.port ||
                url.query != null ||
                url.fragment != null ||
                url.username.isNotEmpty() ||
                url.password.isNotEmpty()
            ) {
                continue
            }
            val name = url.pathSegments.last()
            if (!isRemoteBackupName(name) ||
                url.encodedPath != root.newBuilder().addPathSegment(name).build().encodedPath
            ) {
                continue
            }
            val length =
                item.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.toLongOrNull() ?: -1
            if (length !in 0..MAX_REMOTE_BYTES) continue
            add(RemoteObject(name, name, length))
        }
    }.distinctBy { it.id }
}

/** Decode once before the DTD policy, including XML's Unicode signatures and declared charset. */
internal fun decodeWebDavXml(bytes: ByteArray, httpCharset: Charset? = null): String {
    require(bytes.size <= 2 * 1024 * 1024)
    val prefix = bytes.take(4).map { it.toInt() and 255 }
    val unicode = when {
        prefix == listOf(0, 0, 254, 255) || prefix == listOf(0, 0, 0, 60) -> "UTF-32BE"
        prefix == listOf(255, 254, 0, 0) || prefix == listOf(60, 0, 0, 0) -> "UTF-32LE"
        prefix.take(2) == listOf(254, 255) || prefix == listOf(0, 60, 0, 63) -> "UTF-16BE"
        prefix.take(2) == listOf(255, 254) || prefix == listOf(60, 0, 63, 0) -> "UTF-16LE"
        prefix.take(3) == listOf(239, 187, 191) -> "UTF-8"
        else -> null
    }
    val declared = Regex("^\\s*<\\?xml[^>]{0,256}encoding\\s*=\\s*[\"']([A-Za-z0-9._-]{1,40})[\"']")
        .find(bytes.take(512).toByteArray().toString(Charsets.US_ASCII))?.groupValues?.get(1)
    val charset = unicode?.let(Charset::forName) ?: httpCharset ?: declared?.let(Charset::forName) ?: Charsets.UTF_8
    return bytes.toString(charset).removePrefix("\uFEFF")
}
