package it.lorelore.nyanime.cloud.sync

import kotlinx.serialization.Serializable
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.syncJson
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID

/** Authenticated inside the parent envelope. Each bounded legacy chunk keeps its own AEAD. */
@Serializable
data class SyncPackedChunk(val id: String, val hash: String, val bytes: Int, val offset: Long)

@Serializable
data class SyncCheckpointPackIndex(
    val version: Int = 1,
    val checkpoint: String,
    val chunks: List<SyncPackedChunk>,
) {
    fun validate() {
        require(version == 1 && UUID.fromString(checkpoint).toString() == checkpoint)
        require(chunks.size in 1..SyncCheckpointPack.MAX_CHUNKS)
        require(chunks.map { it.id }.distinct().size == chunks.size)
        var offset = 0L
        chunks.forEach {
            require(UUID.fromString(it.id).toString() == it.id)
            require(it.hash.matches(Regex("[a-f0-9]{64}")))
            require(it.bytes in 1..SyncCheckpointPack.MAX_FRAME_BYTES && it.offset == offset)
            offset = Math.addExact(offset, 4L + it.bytes)
            require(offset <= SyncCheckpointPack.MAX_BYTES - SyncCheckpointPack.MAX_HEADER_BYTES)
        }
    }
}

/** Disk-streamed framing, never one giant JSON array or unbounded plaintext allocation. */
object SyncCheckpointPack {
    const val VERSION = 1
    const val MAX_CHUNKS = 128
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_HEADER_BYTES = 128 * 1024
    const val MAX_FRAME_BYTES = 12 * 1024 * 1024
    private val magic = "NYANIME_SYNC_PACK_1\n".toByteArray(Charsets.US_ASCII)

    fun entry(packet: SyncPacket, offset: Long): SyncPackedChunk {
        packet.validate()
        require(packet.kind == "checkpoint")
        return SyncPackedChunk(packet.id, SyncCheckpoints.hash(packet.payload), encode(packet).size, offset)
    }

    fun write(output: OutputStream, parent: SyncPacket, packets: Sequence<SyncPacket>) {
        parent.validate()
        require(parent.kind == "checkpoint-pack")
        val header = encode(parent)
        require(header.size in 1..MAX_HEADER_BYTES)
        val stream = DataOutputStream(output)
        stream.write(magic)
        stream.writeInt(header.size)
        stream.write(header)
        val ids = mutableSetOf(parent.id)
        var total = magic.size + 4L + header.size
        var count = 0
        packets.forEach {
            it.validate()
            require(it.kind == "checkpoint" && it.space == parent.space && ids.add(it.id))
            val bytes = encode(it)
            require(bytes.size in 1..MAX_FRAME_BYTES && ++count <= MAX_CHUNKS)
            total = Math.addExact(total, 4L + bytes.size)
            require(total <= MAX_BYTES)
            stream.writeInt(bytes.size)
            stream.write(bytes)
        }
        require(count > 0)
        stream.flush()
    }

    /** Structural validation before storing a receive cursor; no keys or plaintext needed. */
    fun inspect(file: File): SyncPacket {
        require(file.length() in 1..MAX_BYTES)
        file.inputStream().buffered().use { input ->
            val stream = DataInputStream(input)
            val parent = header(stream)
            val ids = mutableSetOf(parent.id)
            var count = 0
            while (true) {
                val packet = frame(stream) ?: break
                require(++count <= MAX_CHUNKS && packet.kind == "checkpoint")
                require(packet.space == parent.space && ids.add(packet.id))
            }
            require(count > 0)
            return parent
        }
    }

    /** Bounded random access; no open descriptor survives a caller's cancellation or lazy iterator. */
    fun packet(file: File, parent: SyncPacket, index: SyncCheckpointPackIndex, position: Int): SyncPacket {
        index.validate()
        require(position in index.chunks.indices && file.length() in 1..MAX_BYTES)
        RandomAccessFile(file, "r").use { input ->
            require(ByteArray(magic.size).also(input::readFully).contentEquals(magic))
            val headerSize = input.readInt().also { require(it in 1..MAX_HEADER_BYTES) }
            require(decode(ByteArray(headerSize).also(input::readFully)) == parent)
            val start = input.filePointer
            val last = index.chunks.last()
            require(input.length() == start + last.offset + 4L + last.bytes)
            val expected = index.chunks[position]
            input.seek(start + expected.offset)
            require(input.readInt() == expected.bytes)
            val packet = decode(ByteArray(expected.bytes).also(input::readFully))
            require(packet.kind == "checkpoint" && packet.id == expected.id && packet.space == parent.space)
            require(SyncCheckpoints.hash(packet.payload) == expected.hash)
            return packet
        }
    }

    private fun header(stream: DataInputStream): SyncPacket {
        val prefix = ByteArray(magic.size).also(stream::readFully)
        require(prefix.contentEquals(magic))
        val parent = decode(requireNotNull(rawFrame(stream, MAX_HEADER_BYTES)))
        require(parent.kind == "checkpoint-pack")
        return parent
    }

    private fun frame(stream: DataInputStream): SyncPacket? = rawFrame(stream, MAX_FRAME_BYTES)?.let(::decode)

    private fun rawFrame(stream: DataInputStream, limit: Int): ByteArray? {
        val first = stream.read()
        if (first < 0) return null
        val length = (first shl 24) or (stream.readUnsignedByte() shl 16) or
            (stream.readUnsignedByte() shl 8) or stream.readUnsignedByte()
        require(length in 1..limit)
        return ByteArray(length).also(stream::readFully)
    }

    private fun encode(packet: SyncPacket) = syncJson.encodeToString(packet).toByteArray(Charsets.UTF_8)
    private fun decode(bytes: ByteArray): SyncPacket =
        syncJson.decodeFromString<SyncPacket>(bytes.decodeToString(throwOnInvalidSequence = true)).also {
            it.validate()
        }
}
