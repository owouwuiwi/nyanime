package it.lorelore.nyanime.novel

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.zip.ZipFile

/**
 * Bounded structural inspection before the toolkit parses metadata. No entities or URLs are
 * fetched.
 */
internal object NovelEpubPolicy {
    private const val MAX_XML_BYTES = 2 * 1024 * 1024
    private const val MAX_ELEMENTS = 20_000
    private const val MAX_DEPTH = 96
    private val FONT_OBFUSCATION =
        setOf(
            "http://www.idpf.org/2008/embedding",
            "http://ns.adobe.com/pdf/enc#RC",
        )

    data class Inspection(val unsupportedEncryption: Boolean, val readingLengths: Map<String, Long>)

    fun inspect(file: File, checkpoint: () -> Unit = {}): Inspection =
        ZipFile(file).use { zip ->
            val container = xml(zip, "META-INF/container.xml", checkpoint)
            val packages =
                container
                    .getAllElements()
                    .filter { it.localName() == "rootfile" }
                    .map { it.attr("full-path") }
                    .distinct()
            require(packages.isNotEmpty() && packages.size <= 16) { "Unsupported EPUB rootfiles" }
            val readingLengths = mutableMapOf<String, Long>()
            for (path in packages) {
                checkpoint()
                val packagePath =
                    localPath("", path) ?: throw IOException("Unsafe EPUB package reference")
                val packageXml =
                    xml(zip, packagePath, checkpoint).children().firstOrNull {
                        it.localName() == "package"
                    } ?: throw IOException("Missing EPUB package")
                val manifest =
                    packageXml
                        .children()
                        .firstOrNull { it.localName() == "manifest" }
                        ?.children()
                        ?.filter { it.localName() == "item" }
                        .orEmpty()
                require(manifest.size <= 10_000) { "EPUB manifest exceeds its resource budget" }
                val byId = manifest.associateBy { it.attr("id") }
                require(byId.size == manifest.size && byId.keys.none(String::isBlank)) {
                    "Invalid EPUB manifest identity"
                }
                val spine =
                    packageXml
                        .children()
                        .firstOrNull { it.localName() == "spine" }
                        ?.children()
                        ?.filter { it.localName() == "itemref" }
                        .orEmpty()
                require(spine.isNotEmpty() && spine.size <= 10_000) {
                    "Unsupported EPUB reading order"
                }
                for (item in spine) {
                    checkpoint()
                    val resource =
                        byId[item.attr("idref")] ?: throw IOException("Missing EPUB spine resource")
                    val resolved =
                        localPath(packagePath, resource.attr("href"))
                            ?: throw IOException(
                                "Remote reading order is unavailable for a local book",
                            )
                    val entry = zip.getEntry(resolved)
                    require(
                        entry != null && !entry.isDirectory && entry.size in 1..(32L * 1024 * 1024),
                    ) {
                        "Missing or oversized EPUB chapter"
                    }
                    if (
                        resource.attr("media-type").substringBefore(';') in
                        setOf("application/xhtml+xml", "text/html", "image/svg+xml")
                    ) {
                        require(entry.size <= NovelHtmlPolicy.MAX_HTML_BYTES) {
                            "EPUB chapter exceeds its readable byte budget"
                        }
                    }
                    readingLengths[resolved] = entry.size
                }
                // NCX/nav are also parsed by the toolkit, so apply the same preflight budgets.
                for (item in manifest) {
                    val navigation =
                        item.attr("media-type") == "application/x-dtbncx+xml" ||
                            "nav" in item.attr("properties").split(Regex("\\s+"))
                    if (navigation) {
                        val target =
                            localPath(packagePath, item.attr("href"))
                                ?: throw IOException(
                                    "Remote navigation is unavailable for a local book",
                                )
                        xml(zip, target, checkpoint)
                    }
                }
            }
            val encryption =
                zip.getEntry("META-INF/encryption.xml")
                    ?: return@use Inspection(false, readingLengths)
            require(!encryption.isDirectory)
            val methods =
                xml(zip, encryption.name, checkpoint)
                    .getAllElements()
                    .filter { it.localName() == "EncryptionMethod" }
                    .map { it.attr("Algorithm") }
            Inspection(methods.isEmpty() || methods.any { it !in FONT_OBFUSCATION }, readingLengths)
        }

    /** Resolve standard parent segments within an archive, never beyond its root. */
    private fun localPath(base: String, reference: String): String? = runCatching {
        if (reference.isBlank() || reference.length > 2048 || '\\' in reference) return null
        val ref = URI(reference)
        if (ref.isAbsolute || ref.rawAuthority != null || ref.rawPath.startsWith('/')) return null
        val normalized = URI(null, null, base, null).resolve(ref).normalize()
        val path = normalized.path
        if (
            path.isBlank() ||
            path.startsWith('/') ||
            path.split('/').any { it == ".." || it == "." || it.isBlank() }
        ) {
            return null
        }
        path
    }
        .getOrNull()

    private fun xml(zip: ZipFile, path: String, checkpoint: () -> Unit): Element {
        checkpoint()
        val entry = zip.getEntry(path) ?: throw IOException("Missing EPUB metadata")
        require(!entry.isDirectory && entry.size in 1..MAX_XML_BYTES.toLong()) {
            "EPUB metadata exceeds its byte budget"
        }
        val bytes =
            zip.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() + count <= MAX_XML_BYTES) {
                        "EPUB metadata exceeds its byte budget"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        require(bytes.count { it == '<'.code.toByte() } <= MAX_ELEMENTS * 3) {
            "EPUB metadata exceeds its syntax budget"
        }
        // Jsoup's XML parser resolves only its fixed entity table; it has no external entity
        // resolver.
        val document = Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
        var count = 0
        val stack = ArrayDeque<Pair<Element, Int>>()
        stack.add(document to 0)
        while (stack.isNotEmpty()) {
            checkpoint()
            val (element, depth) = stack.removeLast()
            require(++count <= MAX_ELEMENTS && depth <= MAX_DEPTH) {
                "EPUB metadata exceeds its structure budget"
            }
            element.children().forEach { stack.add(it to depth + 1) }
        }
        return document
    }

    private fun Element.localName() = tagName().substringAfter(':')
}
