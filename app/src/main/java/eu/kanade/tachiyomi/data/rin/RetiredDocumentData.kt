package eu.kanade.tachiyomi.data.rin

import android.app.NotificationManager
import android.content.Context
import android.util.AtomicFile
import androidx.work.WorkManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.TimeUnit

/** Upgrade cleanup for the retired document medium; never identifies individual sources. */
internal object RetiredDocumentData {
    fun isRetired(value: JsonElement): Boolean =
        ((value as? JsonObject)?.get("manifest") as? JsonObject)?.get("medium")?.jsonPrimitive?.content ==
            "publication"

    fun retain(items: JsonArray): JsonArray = JsonArray(items.filterNot(::isRetired))

    fun pending(value: JsonObject): JsonObject? {
        val entries = value["entries"] as? JsonArray ?: return value
        val retained = retain(entries)
        val prepared = value["prepared"] as? JsonArray ?: JsonArray(emptyList())
        val retainedPrepared = retain(prepared)
        if (retained == entries && retainedPrepared == prepared) return value
        if (retained.isEmpty()) return null
        return JsonObject(
            value +
                mapOf(
                    "entries" to retained,
                    "prepared" to retainedPrepared,
                ),
        )
    }

    /** Run before loading any RIN. Small registry writes are atomic; unrelated records remain intact. */
    fun cleanRegistry(context: Context) {
        try {
            val root = File(context.filesDir, "rin")
            val index = AtomicFile(File(root, "active.json"))
            val removed = mutableListOf<JsonElement>()
            if (index.baseFile.exists()) {
                val current = RinArchive.json.parseToJsonElement(
                    index.openRead().use { it.readBytes().decodeToString() },
                ) as JsonArray
                val retained = retain(current)
                if (retained != current) {
                    val output = index.startWrite()
                    try {
                        output.write(retained.toString().toByteArray())
                        index.finishWrite(output)
                    } catch (error: Exception) {
                        index.failWrite(output)
                        throw error
                    }
                    removed += current.filter(::isRetired)
                }
            }
            val catalogue = context.getSharedPreferences("rin_state", Context.MODE_PRIVATE)
            val raw = catalogue.getString("catalogue", null)
            if (raw != null) {
                val current = RinArchive.json.parseToJsonElement(raw) as JsonArray
                val retained = retain(current)
                if (retained != current) check(catalogue.edit().putString("catalogue", retained.toString()).commit())
            }
            val migration = context.getSharedPreferences("rin_migration", Context.MODE_PRIVATE)
            val queued = migration.getString("pending", null)
            if (queued != null) {
                val current = RinArchive.json.parseToJsonElement(queued) as JsonObject
                val retained = pending(current)
                if (retained != current) {
                    val editor = migration.edit()
                    if (retained == null) editor.remove("pending") else editor.putString("pending", retained.toString())
                    check(editor.commit())
                    removed += (current["prepared"] as? JsonArray).orEmpty().filter(::isRetired)
                }
            }
            removed.mapNotNull { (it as? JsonObject)?.get("file")?.jsonPrimitive?.content }
                .filter { it.matches(Regex("[a-f0-9]{64}\\.rin")) }.distinct()
                .forEach { File(root, it).delete() }
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Unable to clean retired document registrations" }
        }
    }

    /** Background cleanup cannot change the player, manga library or ordinary downloads. */
    fun cleanFiles(context: Context) {
        try {
            val preferences = context.getSharedPreferences("rin_state", Context.MODE_PRIVATE)
            if (preferences.getBoolean("retired_document_files_cleaned", false)) return
            WorkManager.getInstance(context)
                .cancelAllWorkByTag("eu.kanade.tachiyomi.data.publication.PublicationDownloadWorker")
                .result.get(30, TimeUnit.SECONDS)
            context.getSystemService(NotificationManager::class.java).deleteNotificationChannel("publication-downloads")
            listOf(
                File(context.filesDir, "publications"),
                File(context.filesDir, "mozilla"),
                File(context.cacheDir, "publications"),
                File(context.cacheDir, "publication-covers"),
                File(context.cacheDir, "mozilla"),
            ).forEach { directory ->
                if (directory.exists()) check(directory.deleteRecursively())
            }
            check(preferences.edit().putBoolean("retired_document_files_cleaned", true).commit())
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Unable to clean retired document files" }
        }
    }
}
