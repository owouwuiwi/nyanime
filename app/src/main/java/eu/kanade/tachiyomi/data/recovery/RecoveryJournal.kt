package eu.kanade.tachiyomi.data.recovery

import android.content.Context
import android.util.AtomicFile
import eu.kanade.tachiyomi.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded health diagnostics, enabled only after explicit recovery authorization. */
object RecoveryJournal {
    @Synchronized fun record(context: Context, state: String) {
        if (!RecoveryAccess(context).hasGrants()) return
        runCatching {
            val now = System.currentTimeMillis()
            val old = read(context)
            val crashes = old.optJSONArray("crashes") ?: JSONArray()
            val times = if (old.optInt("versionCode") == BuildConfig.VERSION_CODE) {
                (0 until crashes.length()).map(crashes::getLong).filter { it >= now - 24 * 60 * 60 * 1000L }
            } else {
                emptyList()
            }
            val file = AtomicFile(File(context.filesDir, "recovery-health.json"))
            val output = file.startWrite()
            try {
                output.write(
                    JSONObject().put("versionCode", BuildConfig.VERSION_CODE)
                        .put("state", state).put("at", now)
                        .put(
                            "crashes",
                            JSONArray(
                                (
                                    times +
                                        if (state ==
                                            "crashed"
                                        ) {
                                            listOf(now)
                                        } else {
                                            emptyList()
                                        }
                                    ).takeLast(5),
                            ),
                        )
                        .toString().toByteArray(),
                )
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        }
    }
    fun read(context: Context): JSONObject = runCatching {
        JSONObject(
            AtomicFile(File(context.filesDir, "recovery-health.json")).openRead()
                .use { it.readBytes().toString(Charsets.UTF_8) },
        )
    }.getOrDefault(JSONObject())
}
