package it.lorelore.nyanime.sources

import android.content.Context
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Bounded, rebuildable freshness metadata, outside the user's library and portable backups. */
internal object DetailRefreshes {
    private var instance: DetailRefreshCoordinator? = null

    @Synchronized
    fun get(context: Context): DetailRefreshCoordinator = instance ?: run {
        val preferences = context.applicationContext.getSharedPreferences("detail-freshness-v1", Context.MODE_PRIVATE)
        val storage = object : DetailFreshnessStorage {
            override fun read(key: String): DetailRefreshStamp? = runCatching {
                preferences.getString(key, null)?.let { Json.decodeFromString<DetailRefreshStamp>(it) }
            }.getOrNull()

            override fun write(key: String, stamp: DetailRefreshStamp) = synchronized(preferences) {
                val editor = preferences.edit()
                if (preferences.all.size >= 512 && !preferences.contains(key)) {
                    preferences.all.entries.minByOrNull { entry ->
                        runCatching {
                            Json.decodeFromString<DetailRefreshStamp>(entry.value as String).attemptedAt
                        }.getOrDefault(0)
                    }?.let { editor.remove(it.key) }
                }
                editor.putString(key, Json.encodeToString(stamp)).apply()
            }
        }
        DetailRefreshCoordinator(storage).also { instance = it }
    }

    fun key(medium: String, id: Long, source: Long, url: String): String =
        MessageDigest.getInstance("SHA-256").digest("$medium\n$id\n$source\n$url".toByteArray())
            .joinToString("") { "%02x".format(it) }
}
