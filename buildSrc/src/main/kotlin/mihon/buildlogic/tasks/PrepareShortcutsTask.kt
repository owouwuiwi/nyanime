package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

/** The obsolete shortcut plugin requires Groovy 2. Keep native Android XML and wire each variant explicitly. */
@CacheableTask
abstract class PrepareShortcutsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val template: RegularFileProperty

    @get:Input
    abstract val targetPackage: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.file("xml/shortcuts.xml").get().asFile
        output.parentFile.mkdirs()
        output.writeText(ShortcutResource.render(template.get().asFile.readText(), targetPackage.get()))
    }
}

internal object ShortcutResource {
    fun render(xml: String, packageName: String): String {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        val parser = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }.newDocumentBuilder()
        val document = parser.parse(InputSource(StringReader(xml)))
        require(document.documentElement.tagName == "shortcuts")
        val android = "http://schemas.android.com/apk/res/android"
        val intents = document.getElementsByTagName("intent")
        for (index in 0 until intents.length) {
            (intents.item(index) as Element).setAttributeNS(android, "android:targetPackage", packageName)
        }
        val writer = StringWriter()
        TransformerFactory.newInstance().apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }
}
