package eu.kanade.tachiyomi.data.cloud.sync

import android.os.SystemClock
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.number
import nyanime.telegram.tdRequest

/** Causality never depends on time. This clock is for expiry and concurrent-position presentation. */
internal class CloudSyncClock {
    private var serverMillis = System.currentTimeMillis()
    private var elapsed = SystemClock.elapsedRealtime()
    private var refreshed = false

    fun now(): Long = serverMillis + SystemClock.elapsedRealtime() - elapsed

    suspend fun refresh(client: TelegramClient, force: Boolean = false) {
        if (!force && refreshed && SystemClock.elapsedRealtime() - elapsed < 60 * 60_000) return
        val response = client.request(tdRequest("getOption", "name" to JsonPrimitive("unix_time")))
        val seconds = response.number("value")
        require(seconds > 0)
        serverMillis = Math.multiplyExact(seconds, 1000)
        elapsed = SystemClock.elapsedRealtime()
        refreshed = true
    }
}
