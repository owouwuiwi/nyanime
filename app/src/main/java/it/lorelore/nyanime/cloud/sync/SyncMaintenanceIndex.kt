package it.lorelore.nyanime.cloud.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.Serializable
import nyanime.telegram.sync.SyncChange
import nyanime.telegram.sync.SyncCheckpointCommit
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncRegister
import nyanime.telegram.sync.syncJson
import java.io.File
import java.util.UUID

@Serializable
internal data class MaintenanceProof(val delivery: SyncDelivery, val commit: SyncCheckpointCommit, val part: Int = 0)

@Serializable
internal data class MaintenanceCandidate(
    val delivery: SyncDelivery,
    val commit: SyncCheckpointCommit,
    val part: Int = 0,
    val deleting: Boolean = false,
    val deletedParts: Int = 0,
)

@Serializable
internal data class MaintenancePlan(
    val control: String,
    val preparedAt: Long,
    val proofs: List<MaintenanceProof>,
    val anchors: List<Long>,
    val removeCheckpoints: Boolean,
    val checkedAnchors: Int = 0,
    val before: Long = 0,
    val candidate: MaintenanceCandidate? = null,
    val complete: Boolean = false,
    val verifiedAt: Long = 0,
)

/** Rebuildable local index. Reference identities are hashed, causal registers are never approximated. */
internal class SyncMaintenanceIndex(context: Context, account: Long, space: String, chat: Long) : SQLiteOpenHelper(
    context,
    File(context.noBackupFilesDir, "cloud-sync-maintenance-$account-$space-$chat.sqlite").path,
    null,
    1,
) {
    init {
        require(account > 0 && chat < 0 && UUID.fromString(space).toString() == space)
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE coverage (checkpoint TEXT, ref TEXT, field TEXT, data TEXT NOT NULL, " +
                "PRIMARY KEY(checkpoint, ref, field)) WITHOUT ROWID",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun value(key: String): String? = readableDatabase.rawQuery(
        "SELECT value FROM state WHERE key=?",
        arrayOf(key),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun put(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "state",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun plan(): MaintenancePlan? = value("plan")?.takeIf { it.isNotEmpty() }?.let { syncJson.decodeFromString(it) }
    fun save(plan: MaintenancePlan) = put("plan", syncJson.encodeToString(plan))

    fun reset(plan: MaintenancePlan) = transaction { db ->
        db.delete("coverage", null, null)
        save(plan)
    }

    /** Index the verified part and its cursor in one transaction, including conflicting versions. */
    fun addPart(checkpoint: String, changes: List<SyncChange>, advanced: MaintenancePlan) = transaction { db ->
        changes.forEach { change ->
            val ref = change.ref.key()
            val register = register(checkpoint, ref, change.field) ?: SyncRegister()
            val merged = register.merge(change)
            db.insertWithOnConflict(
                "coverage",
                null,
                ContentValues().apply {
                    put("checkpoint", checkpoint)
                    put("ref", ref)
                    put("field", change.field)
                    put("data", syncJson.encodeToString(merged))
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        save(advanced)
    }

    fun covers(plan: MaintenancePlan, changes: List<SyncChange>): Boolean = plan.proofs.size == 2 &&
        plan.proofs.all { proof ->
            changes.all { change ->
                SyncMaintenancePolicy.covered(
                    change,
                    register(proof.commit.id, change.ref.key(), change.field),
                )
            }
        }

    fun removed(count: Int) {
        put("removed", Math.addExact(value("removed")?.toLongOrNull() ?: 0, count.toLong()).toString())
    }

    fun advance(plan: MaintenancePlan, count: Int) = transaction {
        require(count >= 0)
        removed(count)
        save(plan)
    }

    private fun register(checkpoint: String, ref: String, field: String): SyncRegister? =
        readableDatabase.rawQuery(
            "SELECT data FROM coverage WHERE checkpoint=? AND ref=? AND field=?",
            arrayOf(checkpoint, ref, field),
        ).use {
            if (it.moveToFirst()) syncJson.decodeFromString(it.getString(0)) else null
        }

    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }
}
