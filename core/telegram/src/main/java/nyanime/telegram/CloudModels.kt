package nyanime.telegram

import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.UUID

@Serializable
data class CloudManifest(
    val version: Int = 1,
    val backupId: String,
    val deviceId: String,
    val deviceName: String,
    val sha256: String,
    val bytes: Long,
    val appVersion: String,
    val createdAt: Long,
    val protectionSpace: String? = null,
    val protectionEpoch: Long? = null,
) {
    val protected: Boolean get() = version == 2

    fun valid(): Boolean = (
        (version == 1 && protectionSpace == null && protectionEpoch == null) ||
            (version == 2 && protectionSpace?.let(::validId) == true && (protectionEpoch ?: 0) > 0)
        ) &&
        validId(backupId) &&
        validId(deviceId) &&
        deviceName.length in 1..80 &&
        appVersion.length in 1..64 &&
        sha256.matches(Regex("[a-f0-9]{64}")) &&
        bytes in 1..MAX_BYTES &&
        createdAt > 0

    fun caption(): String {
        require(valid())
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            telegramJson.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8),
        )
        return "Nyanime · $deviceName\nBackup completo · $appVersion\nCopia $backupId\n$PREFIX$encoded"
    }

    companion object {
        const val PREFIX = "NYANIME_BACKUP_V1:"
        const val ARCHIVE_MARKER = "Nyanime private backup archive / v1"
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024

        fun parse(caption: String): CloudManifest? = runCatching {
            if (caption.length > 4096) return null
            val line = caption.lineSequence().lastOrNull() ?: return null
            if (!line.startsWith(PREFIX)) return null
            val bytes = Base64.getUrlDecoder().decode(line.removePrefix(PREFIX))
            telegramJson.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8)).takeIf { it.valid() }
        }.getOrNull()

        private fun validId(id: String): Boolean = runCatching {
            UUID.fromString(id).toString() == id
        }.getOrDefault(false)
    }
}

@Serializable
data class CloudCopy(
    val manifest: CloudManifest,
    val accountId: Long,
    val chatId: Long,
    val messageId: Long,
    val fileId: Long,
    val serverDate: Long,
    val pinned: Boolean = false,
)

@Serializable
data class CloudPending(
    val manifest: CloudManifest,
    val accountId: Long,
    val chatId: Long,
    val path: String,
    val messageId: Long = 0,
    val sending: Boolean = false,
    val contentHash: String? = null,
)

data class CloudPage(val copies: List<CloudCopy>, val cursor: Long, val complete: Boolean)

enum class CloudPhase { IDLE, PREPARING, UPLOADING, CHECKING, DOWNLOADING, SAVED, UNCHANGED, WAITING, FAILED }

data class CloudProgress(val phase: CloudPhase = CloudPhase.IDLE, val fraction: Float? = null)

object CloudRetention {
    fun obsolete(copies: List<CloudCopy>, keep: Int, maxAgeDays: Int, now: Long): List<CloudCopy> {
        require(keep >= 1 && maxAgeDays in 0..3650)
        // Server dates and IDs are comparable even when phones have different clocks.
        return copies.distinctBy { it.manifest.backupId }.groupBy { it.manifest.deviceId }.values.flatMap { device ->
            val sorted = device.sortedWith(
                compareByDescending<CloudCopy> {
                    it.serverDate
                }.thenByDescending { it.messageId },
            )
            val protected = sorted.firstOrNull()?.messageId
            val ordinary = sorted.filterNot { it.pinned }
            ordinary.filterIndexed { index, copy ->
                copy.messageId != protected &&
                    (index >= keep || (maxAgeDays > 0 && now - copy.serverDate > maxAgeDays * 86_400L))
            }
        }
    }
}
