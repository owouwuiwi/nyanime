package eu.kanade.tachiyomi.data.cloud

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.builtins.ListSerializer
import nyanime.telegram.CloudCopy
import nyanime.telegram.CloudPending
import nyanime.telegram.telegramJson
import java.io.File
import java.util.UUID

/** Private operational state is deliberately outside the portable application preferences. */
internal class CloudStorage(
    context: Context,
) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "cloud.sqlite").path, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE copies (id TEXT PRIMARY KEY, account INTEGER NOT NULL, data TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun get(key: String): String? = readableDatabase.query(
        "state",
        arrayOf("value"),
        "key=?",
        arrayOf(key),
        null,
        null,
        null,
    ).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
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
    fun remove(key: String) {
        writableDatabase.delete("state", "key=?", arrayOf(key))
    }
    fun clearAccount() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("state", "key<>?", arrayOf("device"))
            db.delete("copies", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    fun deviceId(): String = get("device") ?: UUID.randomUUID().toString().also { put("device", it) }
    fun pending(): CloudPending? = get("pending")?.let { telegramJson.decodeFromString(CloudPending.serializer(), it) }
    fun pending(value: CloudPending) = put("pending", telegramJson.encodeToString(CloudPending.serializer(), value))

    fun copies(account: Long): List<CloudCopy> = readableDatabase.query(
        "copies",
        arrayOf("data"),
        "account=?",
        arrayOf(account.toString()),
        null,
        null,
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(telegramJson.decodeFromString(CloudCopy.serializer(), cursor.getString(0)))
        }
    }
    fun cache(copy: CloudCopy) {
        writableDatabase.insertWithOnConflict(
            "copies",
            null,
            ContentValues().apply {
                put("id", "${copy.accountId}:${copy.manifest.backupId}")
                put("account", copy.accountId)
                put("data", telegramJson.encodeToString(CloudCopy.serializer(), copy))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
    fun deleted(
        copy: CloudCopy,
    ) {
        writableDatabase.delete("copies", "id=?", arrayOf("${copy.accountId}:${copy.manifest.backupId}"))
    }
    fun confirm(copy: CloudCopy) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            cache(copy)
            val contentHash = pending()?.takeIf { it.manifest.backupId == copy.manifest.backupId }?.contentHash
            put("hash:${copy.accountId}:${copy.manifest.deviceId}", contentHash ?: copy.manifest.sha256)
            put("lastSuccess", copy.serverDate.toString())
            remove("pending")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
