package eu.kanade.tachiyomi.data.backup

import java.io.File

/** Remove only caches owned by retired previews; original and custom covers stay untouched. */
object RetiredPreviewData {
    fun cleanArtworkCache(cacheDir: File) {
        listOf("title-artwork-v1", "title-artwork-mappings-v3.json", "title-artwork-mappings-v3.json.pending")
            .forEach { name ->
                val file = File(cacheDir, name)
                check(!file.exists() || file.deleteRecursively()) { "Unable to remove retired preview cache" }
            }
    }
}
