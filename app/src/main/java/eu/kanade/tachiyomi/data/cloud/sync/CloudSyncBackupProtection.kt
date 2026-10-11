package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import nyanime.telegram.CloudManifest
import nyanime.telegram.sync.SyncBackupCipher
import nyanime.telegram.sync.SyncDeviceCrypto
import java.io.File

internal class CloudSyncUnlockRequired(val space: String) : IllegalStateException(
    "Device approval or recovery is required",
)

/** Full cloud backups share the approved private space's keyring, never Telegram's login credentials. */
internal class CloudSyncBackupProtection(private val context: Context) {
    fun protect(file: File, account: Long, backup: String): Pair<String, Long>? {
        val preferences = CloudSyncPreferences()
        if (preferences.account().get() != account || preferences.space().get().isBlank()) return null
        val vault = CloudSyncVault(context, account)
        val keys = CloudSyncKeys.load(vault, preferences.space().get())
            ?: throw CloudSyncUnlockRequired(preferences.space().get())
        val secret = vault.read("device") ?: throw CloudSyncUnlockRequired(keys.space)
        val device = try {
            SyncDeviceCrypto.importAfterLocalUnwrapping(secret)
        } finally {
            secret.fill(0)
        }
        check(keys.contains(device.public)) { "This device no longer has synchronization access" }
        val key = keys.key()
        val encrypted = File(file.parentFile, file.name + ".encrypted")
        try {
            file.inputStream().use { input ->
                encrypted.outputStream().use { output ->
                    SyncBackupCipher.encrypt(input, output, file.length(), account, keys.space, keys.epoch, backup, key)
                }
            }
            // Keep the clear file until the complete authenticated stream was successfully written.
            encrypted.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            return keys.space to keys.epoch
        } finally {
            key.fill(0)
            encrypted.delete()
        }
    }

    fun unprotect(source: File, target: File, account: Long, manifest: CloudManifest) {
        require(manifest.protected)
        val space = requireNotNull(manifest.protectionSpace)
        val keys = CloudSyncKeys.load(CloudSyncVault(context, account), space) ?: throw CloudSyncUnlockRequired(space)
        val key = keys.key(requireNotNull(manifest.protectionEpoch))
        try {
            source.inputStream().use { input ->
                val header = SyncBackupCipher.readHeader(input)
                require(header.protection.space == space && header.protection.epoch == manifest.protectionEpoch)
                require(header.protection.id == manifest.backupId)
                target.outputStream().use { SyncBackupCipher.decrypt(input, it, account, header, key) }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            key.fill(0)
        }
    }
}
