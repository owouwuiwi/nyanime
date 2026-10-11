package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Resolve absent/blank translations at build time, with no work on the UI thread. */
@CacheableTask
abstract class EnglishFallbackResourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val baseFolder: Property<String>

    @get:Inject
    protected abstract val files: FileSystemOperations

    @TaskAction
    fun prepare() {
        val source = inputDirectory.get().asFile
        val output = outputDirectory.get().asFile
        files.sync { from(source); into(output) }
        EnglishResourceFallback.prepare(output, baseFolder.get())
    }
}

internal object EnglishResourceFallback {
    private val kinds = setOf("string", "plurals", "string-array")

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    private fun Element.elements(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }

    private fun Element.key() = tagName + "/" + getAttribute("name")

    private fun Element.blankText() = textContent
        .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
        .trim().removeSurrounding("\"").isBlank()

    fun prepare(root: File, baseFolder: String) {
        val defaults = root.resolve(baseFolder).listFiles().orEmpty()
            .filter { it.extension == "xml" }.flatMap { parse(it).documentElement.elements() }
            .filter { it.tagName in kinds && it.getAttribute("translatable") != "false" }
            .associateBy { it.key() }
        for (folder in root.listFiles().orEmpty().filter { it.isDirectory && it.name != baseFolder }) {
            if (folder.name in setOf("images", "fonts", "files", "assets")) continue
            if (baseFolder == "values") {
                val language = folder.name.removePrefix("values-").substringBefore('-')
                if (!folder.name.startsWith("values-") ||
                    !(language.matches(Regex("[a-z]{2,3}")) || language.startsWith("b+")) ||
                    language == "car"
                ) continue
            }
            val documents = folder.listFiles().orEmpty().filter { it.extension == "xml" }.map { it to parse(it) }
                .filter { (_, document) -> document.documentElement.tagName == "resources" }
            // Image/font folders are not locales.
            if (documents.isEmpty()) continue
            val present = mutableSetOf<String>()
            for ((file, document) in documents) {
                var changed = false
                for (localized in document.documentElement.elements()) {
                    present += localized.key()
                    val english = defaults[localized.key()] ?: continue
                    if (localized.tagName == "string" && localized.blankText()) {
                        document.documentElement.replaceChild(document.importNode(english, true), localized)
                        changed = true
                    } else if (localized.tagName == "plurals") {
                        val items = english.elements().associateBy { it.getAttribute("quantity") }
                        for (item in localized.elements().filter { it.blankText() }) {
                            val fallback = items[item.getAttribute("quantity")] ?: items["other"] ?: continue
                            val replacement = document.importNode(fallback, true) as Element
                            replacement.setAttribute("quantity", item.getAttribute("quantity"))
                            localized.replaceChild(replacement, item)
                            changed = true
                        }
                        if (localized.elements().none { it.getAttribute("quantity") == "other" }) {
                            items["other"]?.let { localized.appendChild(document.importNode(it, true)); changed = true }
                        }
                    } else if (localized.tagName == "string-array") {
                        val items = english.elements()
                        localized.elements().forEachIndexed { index, item ->
                            if (item.blankText() && index < items.size) {
                                localized.replaceChild(document.importNode(items[index], true), item)
                                changed = true
                            }
                        }
                    }
                }
                if (changed) write(document, file)
            }
            val missing = defaults.filterKeys { it !in present }.values
            if (missing.isNotEmpty()) {
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
                val resources = document.createElement("resources")
                document.appendChild(resources)
                missing.forEach { resources.appendChild(document.importNode(it, true)) }
                write(document, folder.resolve("english_fallback.xml"))
            }
        }
    }

    private fun write(document: org.w3c.dom.Document, file: File) {
        TransformerFactory.newInstance().apply {
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "")
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "utf-8")
        }.transform(DOMSource(document), StreamResult(file))
    }
}

