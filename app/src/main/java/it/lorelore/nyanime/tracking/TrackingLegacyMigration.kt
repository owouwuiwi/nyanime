package it.lorelore.nyanime.tracking

import android.content.Context

/** V1 anime/manga writers shared one numeric-key file. Its medium and original account cannot be inferred. */
object TrackingLegacyMigration {
    @Synchronized
    fun migrate(
        context: Context,
        store: PersonalTrackingStore = PersonalTrackingStore.get(context),
        allowIsolated: Boolean = false,
    ) {
        if (eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE && !allowIsolated) return
        val preferences = context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE)
        val original = preferences.all
        val valid = original.mapNotNull { (key, raw) ->
            val id = key.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val value = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1_000_000.0 }
                ?: return@mapNotNull null
            id to value
        }.toMap()
        if (valid.isEmpty()) return
        store.importLegacy(valid)
        // The SQLite import marker prevents resurrection if this preference commit is interrupted.
        val editor = preferences.edit()
        valid.keys.forEach { id ->
            val key = id.toString()
            if (preferences.all[key] == original[key]) editor.remove(key)
        }
        check(editor.commit()) { "Could not finish legacy tracking migration" }
    }
}
