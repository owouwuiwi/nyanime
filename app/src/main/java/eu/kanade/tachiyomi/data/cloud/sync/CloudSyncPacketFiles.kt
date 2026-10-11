package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import android.util.AtomicFile
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncEnvelope
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.syncJson
import java.io.File
import java.util.UUID

/** Encrypted packets are streamed to private disk before advancing a network cursor. */
internal class CloudSyncPacketFiles(context: Context, account: Long, space: String) {
    private val directory = File(context.noBackupFilesDir, "cloud-sync-packets/$account/$space")

    init {
        require(account > 0 && UUID.fromString(space).toString() == space)
    }

    fun resetReceived() {
        directory.listFiles().orEmpty().filter { it.name.startsWith("received-") }.forEach {
            check(it.delete())
        }
    }

    fun stage(delivery: SyncDelivery) {
        delivery.packet.validate()
        require(delivery.receipt.messageId > 0)
        val name = "received-${delivery.receipt.messageId}"
        val file = File(directory, name)
        if (file.exists() || File(file.path + ".bak").exists()) {
            require(this.delivery(delivery.receipt.messageId) == delivery) { "Synchronization message was modified" }
            return
        }
        write(name, syncJson.encodeToString(delivery))
    }

    fun delivery(message: Long): SyncDelivery {
        require(message > 0)
        return syncJson.decodeFromString(read("received-$message"))
    }

    fun retainedDelivery(message: Long): SyncDelivery? = try {
        delivery(message)
    } catch (error: java.io.FileNotFoundException) {
        // A missing disposable packet is different from an invalid packet, which must still fail validation.
        val file = File(directory, "received-$message")
        if (file.exists() || File(file.path + ".bak").exists()) throw error
        null
    }

    fun prepare(packet: SyncPacket) {
        packet.validate()
        write("prepared-${packet.id}", syncJson.encodeToString(packet))
    }

    fun prepared(id: String): SyncPacket {
        require(UUID.fromString(id).toString() == id)
        return syncJson.decodeFromString(read("prepared-$id"))
    }

    fun removePrepared(id: String) {
        require(UUID.fromString(id).toString() == id)
        AtomicFile(File(directory, "prepared-$id")).delete()
    }

    fun removeDelivery(message: Long) {
        require(message > 0)
        AtomicFile(File(directory, "received-$message")).delete()
    }

    private fun read(name: String): String {
        val atomic = AtomicFile(File(directory, name))
        return atomic.openRead().use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= SyncEnvelope.MAX_ENCODED_SIZE + 256 * 1024)
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun write(name: String, contents: String) {
        check(directory.exists() || directory.mkdirs())
        val atomic = AtomicFile(File(directory, name))
        val output = atomic.startWrite()
        try {
            output.write(contents.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
