package eu.kanade.tachiyomi.data.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MigrationDatabaseFilesTest {
    @Test fun databaseWithoutIncomingWalStillReplacesDestinationSidecars() {
        assertEquals(
            family("databases/library.db"),
            MigrationDatabaseFiles.transactionPaths(setOf("databases/library.db")),
        )
    }

    @Test fun incomingJournalIncludesItsDatabaseAndAllSidecars() {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            assertEquals(
                family("databases/library.db"),
                MigrationDatabaseFiles.transactionPaths(setOf("databases/library.db$suffix")),
            )
        }
    }

    @Test fun independentDatabasesAndOtherRootsKeepTheirSeparateRollbackEntries() {
        val paths = setOf("databases/video.data", "databases/books.data-wal", "files/cache", "preferences/main.xml")
        assertEquals(
            family("databases/video.data") +
                family("databases/books.data") +
                setOf("files/cache", "preferences/main.xml"),
            MigrationDatabaseFiles.transactionPaths(paths),
        )
    }

    @Test fun expandingACompleteFamilyIsIdempotent() {
        val paths = family("databases/library")
        assertEquals(paths, MigrationDatabaseFiles.transactionPaths(paths))
    }

    private fun family(base: String) = setOf(base, "$base-wal", "$base-shm", "$base-journal")
}
