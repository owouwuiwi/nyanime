package eu.kanade.tachiyomi.extension

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionPackageDeclarationTest {
    @Test fun `legacy APKs may omit the optional package declaration`() {
        assertNull(ExtensionPackageArchive.declaration(archive(null)))
    }

    @Test fun `invalid declarations cannot silently fall back to a legacy package`() {
        assertThrows(Exception::class.java) { ExtensionPackageArchive.declaration(archive("not JSON")) }
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionPackageArchive.declaration(archive(" ".repeat(ExtensionPackageInspector.MAX_BYTES + 1)))
        }
    }

    private fun archive(declaration: String?): File = File.createTempFile("extension-declaration-", ".apk").apply {
        deleteOnExit()
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(0))
            zip.closeEntry()
            declaration?.let {
                zip.putNextEntry(ZipEntry(ExtensionPackageArchive.ASSET))
                zip.write(it.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
