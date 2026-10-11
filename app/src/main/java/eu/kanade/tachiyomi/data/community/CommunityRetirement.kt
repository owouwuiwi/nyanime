package eu.kanade.tachiyomi.data.community

import java.io.File

/** Exact, feature-owned paths only. Never open/decrypt old stores or touch the library or room keys. */
internal object CommunityRetirement {
    // Keep empty schema tables compatible with installed database versions, but remove capture code and payloads.
    val libraryCleanup = listOf(
        "DROP TRIGGER IF EXISTS community_title_update",
        "DROP TRIGGER IF EXISTS community_item_update",
        "DROP TRIGGER IF EXISTS community_item_insert",
        "DROP TRIGGER IF EXISTS community_title_delete",
        "DROP TRIGGER IF EXISTS community_category_insert",
        "DROP TRIGGER IF EXISTS community_category_delete",
        "DROP TRIGGER IF EXISTS community_category_rename",
        "DROP TRIGGER IF EXISTS community_title_insert",
        "DROP TRIGGER IF EXISTS community_history_insert",
        "DROP TRIGGER IF EXISTS community_history_update",
        "DROP TRIGGER IF EXISTS community_history_delete",
        "DROP TRIGGER IF EXISTS community_category_identity",
        "DROP TRIGGER IF EXISTS community_category_create_record",
        "DROP TRIGGER IF EXISTS community_category_change_record",
        "DROP TRIGGER IF EXISTS community_category_remove_record",
        "DROP TRIGGER IF EXISTS community_category_identity_cleanup",
        "DELETE FROM community_changes",
        "DELETE FROM community_category_ids",
        "DELETE FROM community_removed_categories",
    )

    fun clean(
        noBackup: File,
        databasePath: (String) -> File,
        deleteKey: (String) -> Unit,
        social: Boolean,
        personal: Boolean,
    ): List<String> = buildList {
        fun attempt(name: String, action: () -> Unit) {
            try {
                action()
            } catch (_: Exception) {
                add(name)
            }
        }
        fun remove(file: File) {
            if (file.exists()) check(file.deleteRecursively()) { "Unable to remove obsolete private data" }
        }
        fun retire(identity: String, database: String, alias: String) {
            attempt(alias) { deleteKey(alias) }
            for (suffix in listOf("", ".bak", ".new")) {
                attempt(identity + suffix) { remove(File(noBackup, identity + suffix)) }
            }
            val file = databasePath(database)
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                attempt(database + suffix) { remove(File(file.parentFile, file.name + suffix)) }
            }
        }
        if (personal) {
            retire("personal-sync.identity", "personal-sync-v1.db", "nyanime.personal-sync.local.v1")
        }
        if (social) {
            retire("community.identity", "community-v1.db", "nyanime.community.local.v1")
            attempt("community-image-drafts") { remove(File(noBackup, "community-image-drafts")) }
        }
    }
}
