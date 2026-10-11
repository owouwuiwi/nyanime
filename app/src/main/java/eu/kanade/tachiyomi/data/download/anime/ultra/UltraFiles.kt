package eu.kanade.tachiyomi.data.download.anime.ultra

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.json.JSONObject

internal object UltraFiles {
    const val VIDEO = "Nyanime-Ultra.mkv"
    const val MARKER = "nyanime-ultra.json"
    const val PART = "nyanime-ultra.part"
    private const val FLAG = "nyanime_ultra_v1"

    val memo = JsonObject(mapOf(FLAG to JsonPrimitive(true)))

    fun isUltra(video: Video?): Boolean = video != null &&
        Uri.parse(video.videoUrl).scheme in setOf("file", "content") &&
        (video.memo[FLAG] as? JsonPrimitive)?.booleanOrNull == true

    fun original(folder: UniFile): UniFile? = folder.listFiles().orEmpty().firstOrNull {
        it.isFile && "video" in it.type.orEmpty() && it.name != VIDEO && !it.name.orEmpty().endsWith(".part")
    }

    /** A partial file or a failed export can never enable the Ultra badge. */
    fun completed(context: Context, folder: UniFile): UniFile? = runCatching {
        val video = folder.findFile(VIDEO) ?: return null
        val marker = folder.findFile(MARKER) ?: return null
        if (marker.length() !in 1..4096) return null
        val data = context.contentResolver.openInputStream(marker.uri)!!.bufferedReader().use {
            JSONObject(it.readText())
        }
        video.takeIf {
            data.optInt("version") == 1 &&
                data.optString("preset") == "A+HQ" &&
                data.optLong("bytes") == it.length() &&
                it.length() > 0 &&
                data.optInt("width") > 0 &&
                data.optInt("height") > 0 &&
                data.optLong("durationMs") > 0
        }
    }.getOrNull()

    fun markComplete(context: Context, folder: UniFile, video: UniFile, media: UltraExporter.Media) {
        val data = JSONObject().put("version", 1).put("preset", "A+HQ").put("bytes", video.length())
            .put("width", media.width).put("height", media.height).put("durationMs", media.durationMs)
        val marker = checkNotNull(folder.createFile(MARKER))
        context.contentResolver.openOutputStream(marker.uri, "wt")!!.bufferedWriter().use { it.write(data.toString()) }
        check(completed(context, folder) != null) { "Impossibile confermare il file Ultra" }
    }
}
