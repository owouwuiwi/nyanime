package eu.kanade.tachiyomi.data.migration

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Never follows a link from private rollback data into another directory. */
internal object MigrationFiles {
    fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            file.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(path)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
