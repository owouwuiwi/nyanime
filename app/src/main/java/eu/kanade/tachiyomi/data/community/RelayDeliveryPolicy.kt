package eu.kanade.tachiyomi.data.community

import java.net.URI

/** Diagnostics retain short server explanations but remove addresses, identifiers and encoded payloads. */
internal fun relayDiagnostic(message: String): String = message.take(2000)
    .replace(Regex("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+"), "[address]")
    .replace(Regex("[A-Za-z0-9_+/=-]{40,}"), "[identifier]")
    .replace(Regex("[\\p{Cntrl}]"), " ")
    .take(240)

/** Relay responses are untrusted. Persist only a bounded protocol reason, never echoed payloads. */
internal enum class RelayRejection(
    val code: String,
    val message: String,
    val baseDelay: Long,
    val pausesRelay: Boolean,
) {
    RateLimited("rate-limited", "Limite temporaneo degli invii. Riprovo automaticamente.", 30_000, true),
    Authentication("auth-required", "È richiesta l’autenticazione al relay.", 15_000, true),
    Restricted("restricted", "Il relay limita l’accesso in scrittura. Verifica i relay configurati.", 900_000, false),
    Blocked("blocked", "Il relay non accetta questo invio. Verifica i relay configurati.", 900_000, false),
    ProofOfWork("pow", "Il relay richiede un requisito di pubblicazione non supportato.", 900_000, false),
    Invalid(
        "invalid",
        "Il relay considera non valido questo aggiornamento. I dati locali sono conservati.",
        900_000,
        false,
    ),
    Duplicate("duplicate", "Il relay segnala un duplicato senza confermarne il salvataggio.", 300_000, false),
    Other("error", "Invio non confermato. Riprovo automaticamente.", 5_000, false),
    ;

    fun retryDelay(attempt: Int): Long = (baseDelay * (1L shl (attempt - 1).coerceIn(0, 6))).coerceAtMost(3_600_000)

    fun describe(relay: String): String = "${runCatching { URI(relay).host }.getOrNull() ?: "Relay"}: $message"

    companion object {
        const val SEND_INTERVAL = 500L
        const val ACK_TIMEOUT = 15_000L

        fun parse(message: String): RelayRejection {
            val prefix = message.substringBefore(':').trim().lowercase()
            return entries.find { it.code == prefix } ?: Other
        }
    }
}

/** Shared SQL statements also exercised against SQLite in the queue regression tests. */
internal object CommunityOutboxSql {
    const val PACING_UPGRADE = "ALTER TABLE relay_limits ADD COLUMN pace INTEGER NOT NULL DEFAULT 500"
    const val REDUCE_RATE = "UPDATE relay_limits SET pace=min(30000,max(2000,pace*2)) WHERE relay=?"
    val upgrade = listOf(
        "ALTER TABLE outbox ADD COLUMN priority INTEGER NOT NULL DEFAULT 1",
        "UPDATE outbox SET priority=0 WHERE address LIKE 'nyanime.sync.%'",
        "CREATE TABLE delivery_attempts (event TEXT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE, relay TEXT NOT NULL, attempted INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '', PRIMARY KEY(event,relay))",
        "CREATE TABLE relay_limits (relay TEXT PRIMARY KEY NOT NULL, retry_at INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '')",
        "CREATE INDEX outbox_priority ON outbox(priority DESC,attempted,created)",
    )

    const val DUE = """
        SELECT o.event,coalesce(d.attempts,0) FROM outbox o
        LEFT JOIN delivery_attempts d ON d.event=o.id AND d.relay=?
        WHERE (o.expires=0 OR o.expires>?) AND (o.address NOT LIKE 'nyanime.sync.%' OR CAST(? AS INTEGER)=1)
        AND coalesce(d.retry_at,0)<=CAST(? AS INTEGER)
        AND NOT EXISTS (SELECT 1 FROM receipts r WHERE r.event=o.id AND r.relay=?)
        ORDER BY o.priority DESC,coalesce(d.attempted,0),o.created,o.id
    """
}
