package eu.kanade.tachiyomi.data.migration

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Journal before each rename. A process death restores the destination before its graph can open. */
internal object MigrationTransaction {
    fun begin(context: Context, paths: Set<String>) {
        check(!MigrationState.state(context).has("transaction"))
        val entries = JSONArray()
        val roots = MigrationSnapshot.roots(context)
        // A destination WAL may override the copied database's first page and schema version.
        // Journal the entire family before replacement, even when the source has no WAL.
        MigrationDatabaseFiles.transactionPaths(paths).sorted().forEach { path ->
            require(MigrationArchive.safePath(path) && path.split('/').size == 2)
            val target = File(checkNotNull(roots[path.substringBefore('/')]), path.substringAfter('/'))
            require(!java.nio.file.Files.isSymbolicLink(target.toPath()))
            val backup = File(target.parentFile, ".migration-original-${target.name}")
            check(!backup.exists())
            entries.put(JSONObject().put("path", path).put("existed", target.exists()))
        }
        MigrationState.update(context) {
            it.put("transaction", entries).put("previousComplete", it.optBoolean("complete")).put("complete", false)
        }
        entries.forEachObject { entry ->
            val target =
                File(
                    checkNotNull(roots[entry.getString("path").substringBefore('/')]),
                    entry.getString("path").substringAfter('/'),
                )
            if (entry.getBoolean(
                    "existed",
                )
            ) {
                check(target.renameTo(File(target.parentFile, ".migration-original-${target.name}")))
            }
        }
    }

    fun rollback(context: Context) {
        val state = MigrationState.state(context)
        val entries = state.optJSONArray("transaction") ?: return
        val roots = MigrationSnapshot.roots(context)
        entries.forEachObject { entry ->
            val path = entry.getString("path")
            require(MigrationArchive.safePath(path) && path.split('/').size == 2)
            val target = File(checkNotNull(roots[path.substringBefore('/')]), path.substringAfter('/'))
            val backup = File(target.parentFile, ".migration-original-${target.name}")
            if (backup.exists()) {
                MigrationFiles.deleteTree(target)
                check(backup.renameTo(target))
            } else if (!entry.getBoolean("existed")) {
                MigrationFiles.deleteTree(target)
            }
        }
        MigrationState.update(context) {
            it.remove("transaction")
            if (!it.optBoolean("complete")) it.put("complete", it.optBoolean("previousComplete"))
            it.remove("previousComplete")
        }
    }

    fun finish(context: Context) {
        val state = MigrationState.state(context)
        state.optJSONArray("transaction")?.forEachObject { entry ->
            val path = entry.getString("path")
            require(MigrationArchive.safePath(path))
            val parent = checkNotNull(MigrationSnapshot.roots(context)[path.substringBefore('/')])
            MigrationFiles.deleteTree(File(parent, ".migration-original-${path.substringAfter('/')}"))
        }
        MigrationState.update(context) {
            it.remove("transaction")
            if (!it.optBoolean("complete")) it.put("complete", it.optBoolean("previousComplete"))
            it.remove("previousComplete")
        }
    }
    private fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (index in 0 until length()) action(getJSONObject(index))
    }
}
