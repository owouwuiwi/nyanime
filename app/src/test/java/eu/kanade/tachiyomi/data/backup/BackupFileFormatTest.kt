package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupFileFormatTest {
    @Test fun newBackupsUseNyanimeExtensionAndOlderOnesRemainOpenable() {
        assertTrue(BackupCreator.getFilename().endsWith(".nyabk"))
        assertTrue(BackupFileFormat.acceptsPath("/Download/library.nyabk"))
        assertTrue(BackupFileFormat.acceptsPath("/Download/library.tachibk"))
        assertFalse(BackupFileFormat.acceptsPath("/Download/library.zip"))
    }
}
