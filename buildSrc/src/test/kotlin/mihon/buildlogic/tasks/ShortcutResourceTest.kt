package mihon.buildlogic.tasks

import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutResourceTest {
    @Test
    fun variantPackagesKeepOriginalActionsAndResources() {
        val template = """<shortcuts xmlns:android="http://schemas.android.com/apk/res/android"><shortcut android:shortcutId="library" android:shortcutShortLabel="@string/library"><intent android:action="sample.LIBRARY" android:targetClass="sample.Main" /></shortcut></shortcuts>"""
        for (identity in listOf("sample.reader", "sample.reader.debug")) {
            val result = ShortcutResource.render(template, identity)
            assertTrue(result.contains("android:targetPackage=\"$identity\""))
            assertTrue(result.contains("android:action=\"sample.LIBRARY\""))
            assertTrue(result.contains("android:targetClass=\"sample.Main\""))
            assertTrue(result.contains("@string/library"))
        }
    }

    @Test(expected = org.xml.sax.SAXParseException::class)
    fun externalEntitiesAreRejected() {
        ShortcutResource.render("<!DOCTYPE shortcuts SYSTEM 'file:///unavailable'><shortcuts/>", "sample.reader")
    }
}
