package eu.kanade.tachiyomi.data.community

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString

/** Private data and pending signed envelopes are encrypted at rest with a non-exportable wrapping key. */
internal class CommunityStore(
    context: Context,
    private val vault: IdentityVault,
    database: String = "community-v1.db",
) : SQLiteOpenHelper(
    context,
    database,
    null,
    3,
) {
    var generation: Long = 0
        private set
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE records (bucket TEXT NOT NULL, id TEXT NOT NULL, value BLOB NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(bucket,id))",
        )
        db.execSQL(
            "CREATE TABLE outbox (id TEXT PRIMARY KEY NOT NULL, event BLOB NOT NULL, address TEXT NOT NULL, created INTEGER NOT NULL, expires INTEGER NOT NULL DEFAULT 0, attempted INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE TABLE receipts (event TEXT NOT NULL, relay TEXT NOT NULL, PRIMARY KEY(event,relay))")
        db.execSQL("CREATE TABLE received (id TEXT PRIMARY KEY NOT NULL, at INTEGER NOT NULL)")
        CommunityOutboxSql.upgrade.forEach(db::execSQL)
        db.execSQL(CommunityOutboxSql.PACING_UPGRADE)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) CommunityOutboxSql.upgrade.forEach(db::execSQL)
        if (oldVersion < 3) {
            db.execSQL(CommunityOutboxSql.PACING_UPGRADE)
            // Replace the old burst/backoff cycle with slower continuous replication.
            db.execSQL(
                "UPDATE relay_limits SET retry_at=min(retry_at,?),pace=2000 WHERE reason='rate-limited'",
                arrayOf<Any>(System.currentTimeMillis() + 30_000),
            )
        }
    }
    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }
    fun put(bucket: String, id: String, json: String, at: Long = System.currentTimeMillis()) {
        val inserted = writableDatabase.insertWithOnConflict(
            "records",
            null,
            ContentValues().apply {
                put("bucket", bucket)
                put("id", id)
                put("value", vault.seal(json.toByteArray()))
                put("at", at)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(inserted != -1L) { "Impossibile salvare i dati locali" }
        if (bucket !in
            listOf("settings", "published-at", "winners", "sync", "inbox-relays", "group-rosters")
        ) {
            generation++
        }
    }
    inline fun <reified T> save(
        bucket: String,
        id: String,
        value: T,
        at: Long = System.currentTimeMillis(),
    ) = put(bucket, id, communityJson.encodeToString(value), at)
    fun get(
        bucket: String,
        id: String,
    ): String? = readableDatabase.rawQuery(
        "SELECT value FROM records WHERE bucket=? AND id=?",
        arrayOf(bucket, id),
    ).use {
        if (it.moveToFirst()) vault.open(it.getBlob(0)).decodeToString() else null
    }
    inline fun <reified T> read(
        bucket: String,
        id: String,
    ): T? = get(bucket, id)?.let { communityJson.decodeFromString<T>(it) }
    fun entries(bucket: String, limit: Int = 500): List<Pair<String, String>> = readableDatabase.rawQuery(
        "SELECT id,value FROM records WHERE bucket=? ORDER BY at DESC,id LIMIT ?",
        arrayOf(bucket, limit.coerceIn(1, 20_000).toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.getString(0) to vault.open(cursor.getBlob(1)).decodeToString())
            }
        }
    }
    inline fun <reified T> list(
        bucket: String,
        limit: Int = 500,
    ): List<T> = entries(bucket, limit).map { communityJson.decodeFromString<T>(it.second) }
    fun remove(
        bucket: String,
        id: String,
    ) {
        if (writableDatabase.delete("records", "bucket=? AND id=?", arrayOf(bucket, id)) > 0) generation++
    }
    fun trim(bucket: String, keep: Int) {
        writableDatabase.execSQL(
            "DELETE FROM records WHERE bucket=? AND id NOT IN (SELECT id FROM records WHERE bucket=? ORDER BY at DESC,id LIMIT ?)",
            arrayOf<Any>(bucket, bucket, keep),
        )
    }
    fun seen(
        id: String,
    ): Boolean = readableDatabase.rawQuery("SELECT 1 FROM received WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
    fun markSeen(id: String) {
        writableDatabase.insertWithOnConflict(
            "received",
            null,
            ContentValues().apply {
                put("id", id)
                put("at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }
    fun enqueue(event: NostrEvent, address: String = "", priority: Int = 1) = transaction {
        if (readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE id=?", arrayOf(event.id)).use {
                it.moveToFirst()
            }
        ) {
            return@transaction
        }
        if (address.isNotEmpty()) writableDatabase.delete("outbox", "address=? AND id!=?", arrayOf(address, event.id))
        writableDatabase.execSQL("DELETE FROM receipts WHERE event NOT IN (SELECT id FROM outbox)")
        val inserted = writableDatabase.insertWithOnConflict(
            "outbox",
            null,
            ContentValues().apply {
                put("id", event.id)
                put("event", vault.seal(communityJson.encodeToString(event).toByteArray()))
                put("address", address)
                put("created", event.created_at)
                put("priority", priority)
                put("expires", event.tag("expiration")?.toLongOrNull()?.times(1000) ?: 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        check(inserted != -1L) { "Impossibile salvare l’invio. La modifica resta nella coda locale." }
    }

    /** Claim one eligible event for one relay. ACKs, attempts and cooldowns survive restarts. */
    fun claimNext(
        relay: String,
        includeSync: Boolean,
        accepts: (NostrEvent) -> Boolean,
        now: Long = System.currentTimeMillis(),
    ): NostrEvent? = transaction {
        val blockedUntil = readableDatabase.rawQuery(
            "SELECT retry_at FROM relay_limits WHERE relay=?",
            arrayOf(relay),
        ).use { if (it.moveToFirst()) it.getLong(0) else 0 }
        if (blockedUntil > now) return@transaction null
        val candidate = readableDatabase.rawQuery(
            CommunityOutboxSql.DUE,
            arrayOf(relay, now.toString(), if (includeSync) "1" else "0", now.toString(), relay),
        ).use { cursor ->
            var match: Pair<NostrEvent, Int>? = null
            while (cursor.moveToNext()) {
                val event = communityJson.decodeFromString<NostrEvent>(vault.open(cursor.getBlob(0)).decodeToString())
                if (accepts(event)) {
                    match = event to cursor.getInt(1)
                    break
                }
            }
            match
        } ?: return@transaction null
        val (event, previousAttempts) = candidate
        val attempts = previousAttempts + 1
        val timeout = (RelayRejection.ACK_TIMEOUT * (1L shl previousAttempts.coerceIn(0, 5))).coerceAtMost(300_000)
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO delivery_attempts(event,relay) VALUES(?,?)",
            arrayOf(event.id, relay),
        )
        writableDatabase.execSQL(
            "UPDATE delivery_attempts SET attempted=?,retry_at=?,attempts=? WHERE event=? AND relay=?",
            arrayOf<Any>(now, now + timeout, attempts, event.id, relay),
        )
        writableDatabase.execSQL("UPDATE outbox SET attempted=? WHERE id=?", arrayOf<Any>(now, event.id))
        writableDatabase.execSQL("INSERT OR IGNORE INTO relay_limits(relay) VALUES(?)", arrayOf(relay))
        writableDatabase.execSQL(
            "UPDATE relay_limits SET retry_at=?+pace WHERE relay=?",
            arrayOf<Any>(now, relay),
        )
        event
    }

    fun reject(
        id: String,
        relay: String,
        reason: RelayRejection,
        now: Long = System.currentTimeMillis(),
    ) = transaction {
        val attempts = readableDatabase.rawQuery(
            "SELECT attempts FROM delivery_attempts WHERE event=? AND relay=?",
            arrayOf(id, relay),
        ).use { if (it.moveToFirst()) it.getInt(0) else return@transaction }
        writableDatabase.execSQL(
            "UPDATE delivery_attempts SET retry_at=?,reason=? WHERE event=? AND relay=?",
            arrayOf<Any>(now + reason.retryDelay(attempts), reason.code, id, relay),
        )
        if (reason.pausesRelay) {
            if (reason == RelayRejection.RateLimited) {
                writableDatabase.execSQL(CommunityOutboxSql.REDUCE_RATE, arrayOf(relay))
            }
            val failures = readableDatabase.rawQuery(
                "SELECT failures FROM relay_limits WHERE relay=?",
                arrayOf(relay),
            ).use { if (it.moveToFirst()) it.getInt(0) + 1 else 1 }
            writableDatabase.execSQL(
                "UPDATE relay_limits SET retry_at=?,failures=?,reason=? WHERE relay=?",
                arrayOf<Any>(
                    now +
                        reason.retryDelay(
                            if (reason ==
                                RelayRejection.RateLimited
                            ) {
                                1
                            } else {
                                failures
                            },
                        ),
                    failures,
                    reason.code,
                    relay,
                ),
            )
        }
    }

    fun authenticated(relay: String) = transaction {
        writableDatabase.execSQL(
            "UPDATE relay_limits SET retry_at=0,reason='',failures=0 WHERE relay=? AND reason='auth-required'",
            arrayOf(relay),
        )
        writableDatabase.execSQL(
            "UPDATE delivery_attempts SET retry_at=0,reason='' WHERE relay=? AND reason='auth-required'",
            arrayOf(relay),
        )
    }

    fun retryDeliveries() = transaction {
        // Explicit retry preserves both the data and the existing relay acknowledgements.
        writableDatabase.execSQL("UPDATE relay_limits SET retry_at=0")
        writableDatabase.execSQL("UPDATE delivery_attempts SET retry_at=0")
    }

    fun replicatingCount(): Int = readableDatabase.rawQuery(
        "SELECT count(*) FROM outbox o WHERE EXISTS (SELECT 1 FROM receipts r WHERE r.event=o.id)",
        null,
    ).use {
        it.moveToFirst()
        it.getInt(0)
    }

    fun deliveryIssue(): String? = readableDatabase.rawQuery(
        "SELECT d.relay,d.reason FROM delivery_attempts d WHERE d.reason!='' AND NOT EXISTS (SELECT 1 FROM receipts r WHERE r.event=d.event AND r.relay=d.relay) ORDER BY d.attempted DESC LIMIT 1",
        null,
    ).use { if (it.moveToFirst()) RelayRejection.parse(it.getString(1)).describe(it.getString(0)) else null }

    fun pendingCount(): Int = readableDatabase.rawQuery("SELECT count(*) FROM outbox", null).use {
        it.moveToFirst()
        it.getInt(0)
    }
    fun count(
        bucket: String,
    ): Int = readableDatabase.rawQuery("SELECT count(*) FROM records WHERE bucket=?", arrayOf(bucket)).use {
        it.moveToFirst()
        it.getInt(0)
    }
    fun contains(bucket: String, id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM records WHERE bucket=? AND id=?", arrayOf(bucket, id)).use {
            it.moveToFirst()
        }

    fun syncAddresses(): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM records WHERE bucket='sync-addresses' ORDER BY rowid",
        null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    fun recoveryBatch(): List<String> = transaction {
        val addresses = readableDatabase.rawQuery(
            "SELECT id FROM records WHERE bucket='sync-needed' ORDER BY at,id LIMIT 40",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        addresses.forEach {
            writableDatabase.execSQL(
                "UPDATE records SET at=? WHERE bucket='sync-needed' AND id=?",
                arrayOf<Any>(System.currentTimeMillis(), it),
            )
        }
        addresses
    }
    fun cleanExpired() {
        writableDatabase.delete("outbox", "expires>0 AND expires<?", arrayOf(System.currentTimeMillis().toString()))
        writableDatabase.execSQL("DELETE FROM receipts WHERE event NOT IN (SELECT id FROM outbox)")
    }
    fun recipient(
        eventId: String,
    ): String? = readableDatabase.rawQuery("SELECT event FROM outbox WHERE id=?", arrayOf(eventId)).use {
        if (!it.moveToFirst()) {
            null
        } else {
            communityJson.decodeFromString<NostrEvent>(vault.open(it.getBlob(0)).decodeToString())
                .takeIf { event -> event.kind == 1059 }?.tag("p")
        }
    }
    fun acknowledge(id: String, relay: String, targets: List<String>) {
        if (relay !in targets) return
        transaction {
            if (!readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE id=?", arrayOf(id)).use {
                    it.moveToFirst()
                }
            ) {
                return@transaction
            }
            writableDatabase.insertWithOnConflict(
                "receipts",
                null,
                ContentValues().apply {
                    put("event", id)
                    put("relay", relay)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            writableDatabase.delete("delivery_attempts", "event=? AND relay=?", arrayOf(id, relay))
            writableDatabase.execSQL("UPDATE relay_limits SET failures=0 WHERE relay=?", arrayOf(relay))
            writableDatabase.execSQL(
                "UPDATE relay_limits SET failures=0,reason='' WHERE relay=? AND retry_at<=?",
                arrayOf<Any>(relay, System.currentTimeMillis()),
            )
            val placeholders = targets.joinToString(",") { "?" }
            writableDatabase.delete(
                "receipts",
                "event=? AND relay NOT IN ($placeholders)",
                arrayOf(id, *targets.toTypedArray()),
            )
            val count = readableDatabase.rawQuery("SELECT count(*) FROM receipts WHERE event=?", arrayOf(id)).use {
                it.moveToFirst()
                it.getInt(0)
            }
            if (count >= minOf(2, targets.size)) {
                writableDatabase.delete("outbox", "id=?", arrayOf(id))
                writableDatabase.delete("receipts", "event=?", arrayOf(id))
            }
        }
    }
}
