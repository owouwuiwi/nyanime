package eu.kanade.tachiyomi.data.cleanup

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Removal-only compatibility for retired private stores. Never reads keys, data or room state. */
internal object RetiredProfileData {
    fun clean(noBackup: File, databasePath: (String) -> File, deleteKey: (String) -> Unit): List<String> = buildList {
        fun attempt(name: String, action: () -> Unit) {
            try {
                action()
            } catch (_: Exception) {
                add(name)
            }
        }
        fun remove(file: File) {
            val path = file.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            // No FOLLOW_LINKS: even a malformed draft directory cannot delete unrelated data.
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                        if (error != null) throw error
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        fun retire(identity: String, database: String, alias: String) {
            attempt(alias) { deleteKey(alias) }
            for (suffix in listOf("", ".bak", ".new")) {
                attempt(identity + suffix) { remove(File(noBackup, identity + suffix)) }
            }
            val file = databasePath(database)
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                attempt(database + suffix) { remove(File(file.parentFile, file.name + suffix)) }
            }
        }
        retire("personal-sync.identity", "personal-sync-v1.db", "nyanime.personal-sync.local.v1")
        retire("community.identity", "community-v1.db", "nyanime.community.local.v1")
        attempt("community-image-drafts") { remove(File(noBackup, "community-image-drafts")) }
    }
}
