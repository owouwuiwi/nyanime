package eu.kanade.tachiyomi.data.migration

import android.app.Application
import android.content.Context
import android.util.Base64
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncVault
import eu.kanade.tachiyomi.data.releases.AnimeScheduleTokenStore
import eu.kanade.tachiyomi.data.telegram.TelegramSessionKey
import eu.kanade.tachiyomi.data.watch.WatchRoomArchive
import org.json.JSONArray
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
        if (context.getSharedPreferences("telegram.session.v1", 0).contains("wrapped")) {
            data.put("telegram", TelegramSessionKey.get(context))
        }
        WatchRoomArchive(context).exportMigration()?.let { data.put("room", it) }
        val clouds = JSONArray()
        File(
            context.noBackupFilesDir,
            "cloud-sync-keys",
        ).listFiles().orEmpty().filter(File::isDirectory).forEach { account ->
            val id = account.name.toLongOrNull()?.takeIf { it > 0 } ?: return@forEach
            val vault = CloudSyncVault(context, id)
            account.listFiles().orEmpty().filter { it.name.endsWith(".wrapped") }.forEach { slot ->
                val name = slot.name.removeSuffix(".wrapped")
                val secret = checkNotNull(vault.read(name))
                clouds.put(
                    JSONObject().put("account", id).put("slot", name)
                        .put("value", Base64.encodeToString(secret, Base64.NO_WRAP)),
                )
                secret.fill(0)
            }
        }
        data.put("cloud", clouds)
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
        if (data.has("telegram")) TelegramSessionKey.importMigration(context, data.getString("telegram"))
        if (data.has("room")) WatchRoomArchive(context).importMigration(data.getString("room"))
        val clouds = data.getJSONArray("cloud")
        require(clouds.length() <= 4096)
        for (i in 0 until clouds.length()) {
            val entry = clouds.getJSONObject(i)
            val bytes = Base64.decode(entry.getString("value"), Base64.NO_WRAP)
            CloudSyncVault(context, entry.getLong("account")).write(entry.getString("slot"), bytes)
            bytes.fill(0)
        }
    }
}
