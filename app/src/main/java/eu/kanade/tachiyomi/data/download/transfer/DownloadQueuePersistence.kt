package eu.kanade.tachiyomi.data.download.transfer

import android.content.SharedPreferences
import eu.kanade.tachiyomi.data.download.anime.VideoStorageException

internal data class QueueMigration(val upserts: Map<String, String>, val removals: Set<String>)

/** The old queues shared numeric keys. Separate media namespaces without discarding either queue. */
internal fun queueMigration(entries: Map<String, *>, prefix: String, identify: (String) -> String?): QueueMigration {
    val upserts = mutableMapOf<String, String>()
    val removals = mutableSetOf<String>()
    entries.forEach { (key, value) ->
        if (key.toLongOrNull() != null && value is String) {
            val id = identify(value) ?: return@forEach
            val target = "$prefix:$id"
            if (target !in entries) upserts[target] = value
            removals += key
        }
    }
    return QueueMigration(upserts, removals)
}

internal object DownloadQueuePersistence {
    @Synchronized
    fun migrate(preferences: SharedPreferences, prefix: String, identify: (String) -> String?) {
        val migration = queueMigration(preferences.all, prefix, identify)
        if (migration.removals.isEmpty()) return
        val edit = preferences.edit()
        migration.upserts.forEach { (key, value) -> edit.putString(key, value) }
        migration.removals.forEach(edit::remove)
        if (!edit.commit()) throw VideoStorageException("Impossibile conservare la coda download.")
    }
}
