package eu.kanade.tachiyomi.data.migration

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.data.releases.AnimeScheduleTokenStore
import eu.kanade.tachiyomi.data.watch.WatchRoomArchive
import org.json.JSONObject
import java.io.File

/** Component-owned adapters unwrap values in the source and rewrap them with destination keys. */
internal object MigrationSecrets {
    fun export(context: Context, file: File) {
        val data = JSONObject().put("version", 1)
        if (context.getSharedPreferences("anime_schedule_credentials", 0).contains("token")) {
            data.put(
                "schedule",
                checkNotNull(AnimeScheduleTokenStore(context.applicationContext as Application).read()),
            )
        }
        WatchRoomArchive(context).exportMigration()?.let { data.put("room", it) }
        file.writeText(data.toString())
    }

    fun restore(context: Context, file: File) {
        require(file.length() <= 16 * 1024 * 1024)
        val data = JSONObject(file.readText())
        require(data.getInt("version") == 1)
        if (data.has(
                "schedule",
            )
        ) {
            AnimeScheduleTokenStore(context.applicationContext as Application).save(data.getString("schedule"))
        }
        if (data.has("room")) WatchRoomArchive(context).importMigration(data.getString("room"))
    }
}
