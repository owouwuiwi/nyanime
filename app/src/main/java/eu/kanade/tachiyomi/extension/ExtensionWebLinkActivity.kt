package eu.kanade.tachiyomi.extension

import android.R.attr
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Classifies declarations in an already authenticated APK. Private imports never run these activities. */
internal object ExtensionWebLinkActivity {
    fun isOptionalOnly(file: File, packageName: String, expectedActivityNames: Set<String>): Boolean {
        if (expectedActivityNames.isEmpty()) return false
        return try {
            val manifest = readManifest(file)
            if (manifest.name != "manifest" || manifest.attributes["package"]?.let {
                    it.namespace.isEmpty() && it.resourceId == 0 && it.string == packageName
                } != true
            ) return false

            val app = manifest.children.singleOrNull { it.name == "application" } ?: return false
            if (manifest.children.any { it !== app && (it.name !in MANIFEST_METADATA || it.children.isNotEmpty()) }) {
                return false
            }
            if (!app.hasOnlyAttributes(APPLICATION_ATTRIBUTES) || app.attributes["name"]?.let {
                    it.string != "android.app.Application"
                } == true
            ) return false
            if (app.attributes["enabled"]?.let { it.boolean != true } == true ||
                app.attributes["hasCode"]?.let { it.boolean != true } == true
            ) return false

            val names = mutableSetOf<String>()
            for (component in app.children) {
                if (component.name == "meta-data" && component.children.isEmpty()) continue
                if (component.name != "activity" || !component.hasOnlyAttributes(ACTIVITY_ATTRIBUTES) ||
                    component.attributes["exported"]?.boolean != true ||
                    component.attributes["enabled"]?.let { it.boolean != true } == true ||
                    component.children.isEmpty() || component.children.any { !it.isWebLinkFilter() }
                ) return false

                val name = component.attributes["name"]?.string ?: return false
                if (!CLASS_NAME.matches(name)) return false
                val qualifiedName = when {
                    name.startsWith('.') -> packageName + name
                    '.' !in name -> "$packageName.$name"
                    else -> name
                }
                if (!names.add(qualifiedName)) return false
            }
            names == expectedActivityNames
        } catch (_: Exception) {
            false
        }
    }

    private fun Element.isWebLinkFilter(): Boolean {
        if (name != "intent-filter" || !hasOnlyAttributes(FILTER_ATTRIBUTES) ||
            attributes["autoVerify"]?.let { it.boolean == null } == true
        ) return false
        var view = false
        val categories = mutableSetOf<String>()
        val dataFields = mutableSetOf<String>()
        for (child in children) {
            if (child.children.isNotEmpty()) return false
            when (child.name) {
                "action" -> {
                    if (view || child.intentName() != "android.intent.action.VIEW") return false
                    view = true
                }
                "category" -> {
                    val category = child.intentName() ?: return false
                    if (category !in WEB_CATEGORIES || !categories.add(category)) return false
                }
                "data" -> {
                    if (child.attributes.isEmpty() || !child.hasOnlyAttributes(DATA_ATTRIBUTES)) return false
                    for ((field, attribute) in child.attributes) {
                        val value = attribute.string ?: return false
                        val valid = when (field) {
                            "scheme" -> value == "http" || value == "https"
                            "host" -> value.length <= 253 && value.split('.').all(DNS_LABEL::matches)
                            else -> value.startsWith('/') && value.none { it.isISOControl() || it.isWhitespace() }
                        }
                        if (!valid) return false
                        dataFields += field
                    }
                }
                else -> return false
            }
        }
        return view && categories == WEB_CATEGORIES && "scheme" in dataFields && "host" in dataFields
    }

    private fun Element.intentName(): String? =
        attributes["name"]?.string.takeIf { attributes.size == 1 && hasOnlyAttributes(NAME_ATTRIBUTE) }

    private fun Element.hasOnlyAttributes(allowed: Map<String, Int>): Boolean = attributes.all { (name, attribute) ->
        attribute.namespace == ANDROID_NAMESPACE && allowed[name] == attribute.resourceId
    }

    private fun readManifest(file: File): Element {
        val bytes = ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().single { it.name == ApkUtils.ANDROID_MANIFEST_ZIP_ENTRY_NAME }
            require(entry.size in 8..MAX_MANIFEST_BYTES)
            DataInputStream(zip.getInputStream(entry)).use { stream ->
                ByteArray(entry.size.toInt()).also {
                    stream.readFully(it)
                    require(stream.read() == -1)
                }
            }
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        validateChunkBoundaries(buffer)
        val parser = AndroidBinXmlParser(buffer)
        val stack = ArrayDeque<Element>()
        var root: Element? = null
        var elements = 0
        while (true) {
            when (parser.next()) {
                AndroidBinXmlParser.EVENT_START_ELEMENT -> {
                    require(++elements <= 512 && stack.size < 8 && parser.namespace.isEmpty())
                    require(parser.attributeCount <= 64)
                    val attributes = buildMap {
                        repeat(parser.attributeCount) { index ->
                            val type = parser.getAttributeValueType(index)
                            val value = Attribute(
                                namespace = parser.getAttributeNamespace(index),
                                resourceId = parser.getAttributeNameResourceId(index),
                                string = if (type == AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                    parser.getAttributeStringValue(index)
                                } else {
                                    null
                                },
                                boolean = if (type == AndroidBinXmlParser.VALUE_TYPE_BOOLEAN) {
                                    parser.getAttributeBooleanValue(index)
                                } else {
                                    null
                                },
                            )
                            require(put(parser.getAttributeName(index), value) == null)
                        }
                    }
                    val element = Element(parser.name, attributes)
                    if (stack.isEmpty()) {
                        require(root == null)
                        root = element
                    } else {
                        stack.last().children += element
                    }
                    stack.addLast(element)
                }
                AndroidBinXmlParser.EVENT_END_ELEMENT -> {
                    require(parser.namespace.isEmpty() && stack.removeLast().name == parser.name)
                }
                AndroidBinXmlParser.EVENT_END_DOCUMENT -> {
                    require(stack.isEmpty())
                    return requireNotNull(root)
                }
                else -> error("Unexpected binary manifest event")
            }
        }
    }

    private fun validateChunkBoundaries(buffer: ByteBuffer) {
        // apksig's manifest reader can report end-of-document after a truncated trailing chunk.
        val length = buffer.limit()
        require(buffer.getShort(0).toInt() == 0x0003 && buffer.getShort(2).toInt() == 8 && buffer.getInt(4) == length)
        var offset = 8
        var allowResourceMap = false
        while (offset < length) {
            require(offset <= length - 8)
            val kind = buffer.getShort(offset).toInt()
            val headerSize = buffer.getShort(offset + 2).toInt()
            val size = buffer.getInt(offset + 4)
            require(size in 8..length - offset && size >= headerSize && size % 4 == 0)
            require(offset != 8 || kind == 0x0001)
            when (kind) {
                0x0001 -> require(offset == 8 && headerSize == 28) // String pool
                0x0180 -> require(allowResourceMap && headerSize == 8) // Resource IDs
                0x0100, 0x0101, 0x0103 -> require(headerSize == 16 && size == 24) // Namespace and end element
                0x0102 -> {
                    require(headerSize == 16 && size >= 36)
                    require(buffer.getShort(offset + 24).toInt() == 20 && buffer.getShort(offset + 26).toInt() == 20)
                    val attributes = buffer.getShort(offset + 28).toInt()
                    require(attributes in 0..64 && size == 36 + 20 * attributes)
                }
                else -> error("Unexpected binary manifest chunk")
            }
            allowResourceMap = kind == 0x0001
            offset += size
        }
    }

    private data class Element(
        val name: String,
        val attributes: Map<String, Attribute>,
        val children: MutableList<Element> = mutableListOf(),
    )

    private data class Attribute(val namespace: String, val resourceId: Int, val string: String?, val boolean: Boolean?)

    private const val MAX_MANIFEST_BYTES = 256L * 1024
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private val MANIFEST_METADATA = setOf("uses-sdk", "uses-feature", "uses-permission", "uses-permission-sdk-23")
    private val CLASS_NAME = Regex("\\.?[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val DNS_LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    private val WEB_CATEGORIES = setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE")
    private val NAME_ATTRIBUTE = mapOf("name" to attr.name)
    private val PRESENTATION_ATTRIBUTES = mapOf("label" to attr.label, "icon" to attr.icon, "theme" to attr.theme)
    private val APPLICATION_ATTRIBUTES = NAME_ATTRIBUTE + PRESENTATION_ATTRIBUTES + mapOf(
        "enabled" to attr.enabled,
        "hasCode" to attr.hasCode,
        "allowBackup" to attr.allowBackup,
        "extractNativeLibs" to attr.extractNativeLibs,
        "roundIcon" to attr.roundIcon,
        "supportsRtl" to attr.supportsRtl,
        "debuggable" to attr.debuggable,
    )
    private val ACTIVITY_ATTRIBUTES = NAME_ATTRIBUTE + PRESENTATION_ATTRIBUTES + mapOf(
        "exported" to attr.exported,
        "enabled" to attr.enabled,
        "excludeFromRecents" to attr.excludeFromRecents,
    )
    private val FILTER_ATTRIBUTES = mapOf("autoVerify" to attr.autoVerify)
    private val DATA_ATTRIBUTES = mapOf(
        "scheme" to attr.scheme,
        "host" to attr.host,
        "path" to attr.path,
        "pathPrefix" to attr.pathPrefix,
        "pathPattern" to attr.pathPattern,
    )
}
