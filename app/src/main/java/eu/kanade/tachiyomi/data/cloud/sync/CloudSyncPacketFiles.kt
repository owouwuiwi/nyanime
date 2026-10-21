package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import android.util.AtomicFile
import it.lorelore.nyanime.cloud.sync.SyncCheckpointPack
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
        AtomicFile(packPath(id, prepared = true)).delete()
    }

    fun removeDelivery(message: Long) {
        require(message > 0)
        retainedDelivery(message)?.takeIf { it.packet.kind == "checkpoint-pack" }?.let {
            AtomicFile(packPath(it.packet.id, prepared = false)).delete()
        }
        AtomicFile(File(directory, "received-$message")).delete()
    }

    fun packFile(id: String, prepared: Boolean = false): File? {
        val file = packPath(id, prepared)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        AtomicFile(file).openRead().close() // Complete AtomicFile's interrupted-write recovery.
        return file
    }

    fun preparePack(parent: SyncPacket, children: List<String>) {
        require(parent.kind == "checkpoint-pack")
        writePack(packPath(parent.id, prepared = true)) { output ->
            SyncCheckpointPack.write(output, parent, children.asSequence().map(::prepared))
        }
        prepare(parent)
    }

    fun materializePack(id: String, destination: File): File {
        val source = requireNotNull(packFile(id, prepared = true))
        if (destination.exists() || File(destination.path + ".bak").exists()) {
            // Android 8–9 can leave a partial base and a valid .bak after process death.
            // Recover only AtomicFile's own previous bytes before checking the immutable identity.
            AtomicFile(destination).openRead().close()
        }
        if (!destination.exists()) {
            check(destination.parentFile!!.exists() || destination.parentFile!!.mkdirs())
            writePack(destination) { output -> copyBounded(source, output) }
        }
        require(digest(destination) == digest(source)) { "Recovery upload was modified" }
        return destination
    }

    /** Only a fully framed encrypted container becomes durable, before its receive cursor. */
    fun stagePack(source: File, id: String): SyncPacket {
        val parent = SyncCheckpointPack.inspect(source)
        require(parent.id == id)
        val target = packFile(id) ?: packPath(id, prepared = false)
        if (target.exists()) {
            require(digest(source) == digest(target)) { "Recovery container identity reused" }
        } else {
            val expected = digest(source)
            writePack(target) { output -> copyBounded(source, output) }
            try {
                require(digest(target) == expected && SyncCheckpointPack.inspect(target) == parent)
            } catch (error: Throwable) {
                AtomicFile(target).delete() // Only this newly created, unaccepted receive copy.
                throw error
            }
        }
        return parent
    }

    private fun packPath(id: String, prepared: Boolean): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, (if (prepared) "prepared-pack-" else "received-pack-") + id)
    }

    private fun copyBounded(source: File, output: java.io.OutputStream) = source.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= SyncCheckpointPack.MAX_BYTES)
            output.write(buffer, 0, count)
        }
    }

    private fun digest(file: File): String = file.inputStream().use { input ->
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writePack(file: File, write: (java.io.OutputStream) -> Unit) {
        check(directory.exists() || directory.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            val buffered = output.buffered()
            write(buffered)
            buffered.flush()
            require(output.channel.size() in 1..SyncCheckpointPack.MAX_BYTES)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
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
