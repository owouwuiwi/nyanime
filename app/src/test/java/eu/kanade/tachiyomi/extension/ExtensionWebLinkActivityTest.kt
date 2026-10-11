package eu.kanade.tachiyomi.extension

import android.R.attr
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionWebLinkActivityTest {
    @TempDir lateinit var directory: Path

    @Test fun `allows multiple activities and web filters with split data declarations`() {
        val mirror = filter(data = listOf(data("host" to "mirror.example", "scheme" to "https", "path" to "/a")))
        val links = activity(".Links", filter(), mirror)
        val other = activity("outside.namespace.LinkActivity", filter())
        val names = setOf("sample.extension.Links", "outside.namespace.LinkActivity")
        assertTrue(optional(apk(manifest(links, other)), names))
    }

    @Test fun `allows a web filter with a DNS hostname and no path restriction`() {
        val link = activity("Links", filter(data = listOf(data("scheme" to "https", "host" to "example.org"))))
        assertTrue(optional(apk(manifest(link))))
    }

    @Test fun `rejects activities that do more than exported web link dispatch`() {
        val invalidFilters = listOf(
            filter(action = "android.intent.action.SEND"),
            filter(categories = listOf("android.intent.category.DEFAULT")),
            filter(categories = categories + "android.intent.category.LAUNCHER"),
            filter().let { it.copy(children = it.children + named("action", "android.intent.action.VIEW")) },
            filter(data = listOf(data("scheme" to "nyanime", "host" to "example.org", "path" to "/title"))),
            filter(data = listOf(data("scheme" to "https", "host" to "example.org"), data("scheme" to "nyanime"))),
            filter(data = listOf(data("scheme" to "https", "host" to "", "path" to "/title"))),
            filter(data = listOf(data("scheme" to "https", "path" to "/title"))),
            filter(data = listOf(data("scheme" to "https", "host" to "example.org", "path" to ""))),
            filter(data = listOf(data("scheme" to "https", "host" to Reference(0x7f010005), "path" to "/title"))),
            filter(data = listOf(data("scheme" to "https", "host" to "example.org", "mimeType" to "text/plain"))),
        )
        invalidFilters.forEach { filter -> assertFalse(optional(apk(manifest(activity(".Links", filter))))) }
        val disabled = activity(".Links", filter()).let { it.copy(attributes = it.attributes + ("enabled" to false)) }
        val hidden = activity(".Links", filter()).let { it.copy(attributes = it.attributes + ("exported" to false)) }
        assertFalse(optional(apk(manifest(disabled))))
        assertFalse(optional(apk(manifest(hidden))))
        assertFalse(optional(apk(manifest(activity(".Links", filter(), invalidFilters.first())))))
    }

    @Test fun `rejects unrelated components and identities inconsistent with package manager`() {
        val link = activity(".Links", filter())
        listOf("activity-alias", "service", "provider", "receiver", "instrumentation").forEach { component ->
            assertFalse(optional(apk(manifest(link, named(component, ".Other")))))
        }
        val customApp = manifest(link).let { root ->
            root.copy(children = root.children.map { it.copy(attributes = mapOf("name" to ".CustomApplication")) })
        }
        assertFalse(optional(apk(customApp)))
        assertFalse(optional(apk(manifest(link, activity("sample.extension.Links", filter())))))
        assertFalse(optional(apk(manifest(link)), names = setOf("sample.extension.Other")))
        assertFalse(optional(apk(manifest(link).copy(attributes = mapOf("package" to "wrong.extension")))))
        assertFalse(optional(apk(manifest(link), ids = attributeIds + ("name" to attr.permission))))
    }

    @Test fun `rejects malformed or non-binary manifests`() {
        assertFalse(optional(zip("<manifest/>".toByteArray())))
        val bytes = binaryManifest(manifest(activity(".Links", filter())), attributeIds)
        assertFalse(optional(zip(bytes.copyOf(bytes.size - 10))))
        val trailing = bytes.copyOf(bytes.size + 1)
        ByteBuffer.wrap(trailing).order(ByteOrder.LITTLE_ENDIAN).putInt(4, trailing.size)
        assertFalse(optional(zip(trailing)))
    }

    private val categories = listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE")

    private fun optional(file: File, names: Set<String> = setOf("sample.extension.Links")) =
        ExtensionWebLinkActivity.isOptionalOnly(file, "sample.extension", names)

    private fun manifest(vararg components: Node) = Node(
        "manifest",
        mapOf("package" to "sample.extension"),
        listOf(Node("application", children = components.toList())),
    )

    private fun activity(name: String, vararg filters: Node) = Node(
        "activity", mapOf("name" to name, "exported" to true, "theme" to Reference(0x01030055)), filters.toList(),
    )

    private fun filter(
        action: String = "android.intent.action.VIEW",
        categories: List<String> = this.categories,
        data: List<Node> = listOf(
            data("scheme" to "http"),
            data("scheme" to "https"),
            data("host" to "example.org"),
            data("pathPattern" to "/title/..*"),
        ),
    ) = Node(
        "intent-filter",
        children = listOf(named("action", action)) + categories.map { named("category", it) } + data,
    )

    private fun named(node: String, name: String) = Node(node, mapOf("name" to name))

    private fun data(vararg attrs: Pair<String, Any>) = Node("data", mapOf(*attrs))

    private fun apk(root: Node, ids: Map<String, Int> = attributeIds) = zip(binaryManifest(root, ids))

    private fun zip(bytes: ByteArray): File = Files.createTempFile(directory, "links-", ".apk").toFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(bytes)
        }
    }

    /** Writes a small binary XML fixture with independently specified typed Android attributes. */
    private fun binaryManifest(root: Node, attributeIds: Map<String, Int>): ByteArray {
        val namespace = "http://schemas.android.com/apk/res/android"
        val strings = linkedSetOf("android", namespace)
        fun collect(node: Node) {
            strings.add(node.name)
            strings.addAll(node.attributes.keys)
            strings.addAll(node.attributes.values.filterIsInstance<String>())
            node.children.forEach(::collect)
        }
        collect(root)
        val indices = strings.withIndex().associate { it.value to it.index }
        val encoded = ByteArrayOutputStream()
        val offsets = strings.map { value ->
            encoded.size().also {
                encoded.word(value.length)
                value.forEach { encoded.word(it.code) }
                encoded.word(0)
            }
        }
        while (encoded.size() % 4 != 0) encoded.write(0)

        fun ByteArrayOutputStream.xmlNodes(node: Node) {
            block(0x0102, 16) {
                integers(0, -1, -1, indices.getValue(node.name))
                words(20, 20, node.attributes.size, 0, 0, 0)
                for ((name, value) in node.attributes) {
                    integer(if (name == "package") -1 else indices.getValue(namespace))
                    integer(indices.getValue(name))
                    integer(if (value is String) indices.getValue(value) else -1)
                    val (type, data) = when (value) {
                        is Boolean -> 0x12 to if (value) -1 else 0
                        is String -> 0x03 to indices.getValue(value)
                        is Reference -> 0x01 to value.id
                        else -> error("Unsupported fixture attribute value")
                    }
                    word(8)
                    write(0)
                    write(type)
                    integer(data)
                }
            }
            node.children.forEach { xmlNodes(it) }
            block(0x0103, 16) { integers(0, -1, -1, indices.getValue(node.name)) }
        }

        return ByteArrayOutputStream().apply {
            block(0x0003, 8) {
                block(0x0001, 28) {
                    integers(strings.size, 0, 0, 28 + offsets.size * 4, 0)
                    offsets.forEach { integer(it) }
                    write(encoded.toByteArray())
                }
                block(0x0180, 8) { strings.forEach { integer(attributeIds[it] ?: 0) } }
                block(0x0100, 16) { integers(0, -1, indices.getValue("android"), indices.getValue(namespace)) }
                xmlNodes(root)
                block(0x0101, 16) { integers(0, -1, indices.getValue("android"), indices.getValue(namespace)) }
            }
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.block(type: Int, headerSize: Int, content: ByteArrayOutputStream.() -> Unit) {
        val payload = ByteArrayOutputStream().apply(content).toByteArray()
        word(type)
        word(headerSize)
        integer(8 + payload.size)
        write(payload)
    }

    private fun ByteArrayOutputStream.word(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.integer(value: Int) {
        word(value)
        word(value ushr 16)
    }

    private fun ByteArrayOutputStream.words(vararg values: Int) = values.forEach { word(it) }

    private fun ByteArrayOutputStream.integers(vararg values: Int) = values.forEach { integer(it) }

    private data class Node(
        val name: String,
        val attributes: Map<String, Any> = emptyMap(),
        val children: List<Node> = emptyList(),
    )
    private data class Reference(val id: Int)

    private val attributeIds = mapOf(
        "name" to attr.name,
        "exported" to attr.exported,
        "enabled" to attr.enabled,
        "theme" to attr.theme,
        "scheme" to attr.scheme,
        "host" to attr.host,
        "path" to attr.path,
        "pathPattern" to attr.pathPattern,
        "mimeType" to attr.mimeType,
    )
}
