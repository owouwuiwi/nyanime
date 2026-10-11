package it.lorelore.nyanime.cloud.sync

import kotlinx.coroutines.CancellationException
import nyanime.telegram.CloudCopy
import nyanime.telegram.CloudRetention
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure

interface BackupMaintenancePort {
    fun state(key: String): String?
    fun state(key: String, value: String)
    suspend fun serverTime(): Long
    suspend fun inventory(): List<CloudCopy>

    /** Revalidate account/archive, manifest and pin state. False means defer for active playback. */
    suspend fun deleteVerified(copy: CloudCopy): Boolean
}

/** Operational metadata is scoped to one account/archive and retries without regenerating a backup. */
class BackupMaintenance(private val port: BackupMaintenancePort) {
    suspend fun run(account: Long, chat: Long, keep: Int, days: Int, now: Long) {
        require(account > 0 && chat < 0)
        if (now < (port.state("retry")?.toLongOrNull() ?: 0)) return
        val last = port.state("last")?.toLongOrNull() ?: 0
        if (port.state("pending") != "true" && now >= last && now - last < 24 * 60 * 60_000L) return
        port.state("pending", "true")
        port.state("retry", (now + 15 * 60_000).toString())
        try {
            val server = port.serverTime()
            require(server > 0)
            val history = port.inventory()
            require(history.all { it.accountId == account && it.chatId == chat && it.manifest.valid() })
            val obsolete = CloudRetention.obsolete(history, if (days > 0) Int.MAX_VALUE else keep, days, server)
            for (copy in obsolete.take(10)) if (!port.deleteVerified(copy)) return
            if (obsolete.size <= 10) {
                port.state("pending", "false")
                port.state("retry", "0")
                port.state("last", now.toString())
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: TelegramException) {
            if (error.failure == TelegramFailure.RATE_LIMIT) {
                port.state("retry", (now + error.retryAfterSeconds.coerceAtLeast(60) * 1000).toString())
            }
        } catch (_: Exception) {
            // Cleanup failure must never turn a confirmed upload into a failed backup.
        }
    }
}
