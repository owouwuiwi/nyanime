package eu.kanade.tachiyomi.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LauncherUpgradeContractTest {
    @Test
    fun updatesReuseTheExistingDefaultAndNeverAddAnotherDefaultEnabledLauncher() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.isFile }
        val xml = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(manifest)
        val nodes = xml.getElementsByTagName("activity-alias")
        val aliases = (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("android:targetActivity") == ".ui.main.LauncherActivity" }
        assertEquals(3, aliases.size)
        val enabled = aliases.filter { it.getAttribute("android:enabled") == "true" }
        assertEquals(listOf(".ui.main.LauncherRed"), enabled.map { it.getAttribute("android:name") })
        assertEquals("@mipmap/ic_launcher_dark", enabled.single().getAttribute("android:icon"))
        for (name in listOf(".ui.main.LauncherClassicRed", ".ui.main.LauncherOrange")) {
            assertFalse(
                aliases.single {
                    it.getAttribute("android:name") == name
                }.getAttribute("android:enabled").toBoolean(),
            )
        }
    }
}
