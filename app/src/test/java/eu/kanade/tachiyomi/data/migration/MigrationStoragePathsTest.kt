package eu.kanade.tachiyomi.data.migration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationStoragePathsTest {
    @Test
    fun `private media roots can be relocated without retaining the previous application path`() {
        for (path in listOf("files", "files/", "external/", "external/downloads/video", "files/books")) {
            assertTrue(MigrationStoragePaths.valid(path), path)
        }
    }

    @Test
    fun `private media mapping refuses database roots traversal and arbitrary absolute destinations`() {
        for (path in listOf(
            "databases/",
            "preferences/",
            "external/../files",
            "files//books",
            "external/books:stream",
            "/external/books",
            "files/./books",
            "external\\books",
        )) {
            assertFalse(MigrationStoragePaths.valid(path), path)
        }
    }
}
