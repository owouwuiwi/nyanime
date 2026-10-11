package eu.kanade.tachiyomi.data.updater.delta

import com.davidehrmann.vcdiff.VCDiffDecoderBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Applies the RFC 3284 default-code-table profile used by the release tools. */
internal object VcdiffPatch {
    private val header = byteArrayOf(0xd6.toByte(), 0xc3.toByte(), 0xc4.toByte(), 0, 0)
    private const val MAX_INLINE_DICTIONARY = 1024 * 1024
    private const val MAX_WINDOW = 2 * 1024 * 1024
    private const val MAX_ENCODED_WINDOW = MAX_WINDOW + 64 * 1024

    fun apply(
        base: File,
        patch: File,
        output: File,
        expected: DeltaRecord,
        checkActive: () -> Unit,
        progress: (Long, Long) -> Unit,
    ) {
        require(expected.baseSize in 1..DeltaPatch.MAX_APK_SIZE && expected.targetSize in 1..DeltaPatch.MAX_APK_SIZE)
        require(expected.patchSize in 1..DeltaPatch.MAX_APK_SIZE && patch.length() == expected.patchSize)
        require(base.length() == expected.baseSize && DeltaPatch.hash(base, checkActive) == expected.baseSha256)
        require(DeltaPatch.hash(patch, checkActive) == expected.patchSha256)
        check(output.createNewFile()) { "Delta output already exists" }
        var complete = false
        try {
            RandomAccessFile(base, "r").use { source ->
                RandomAccessFile(patch, "r").use { input ->
                    FileOutputStream(output).use { destination ->
                        val decoder = VCDiffDecoderBuilder.builder()
                            .withAllowTargetMatches(false)
                            .withMaxTargetWindowSize(MAX_WINDOW)
                            .withMaxTargetFileSize(expected.targetSize)
                            .buildStreaming()
                        val prefix = ByteArray(header.size).also(input::readFully)
                        require(prefix.contentEquals(header)) { "Unsupported VCDIFF header" }
                        val dictionary = if (expected.baseSize <= MAX_INLINE_DICTIONARY) {
                            ByteBuffer.wrap(ByteArray(expected.baseSize.toInt()).also(source::readFully))
                        } else {
                            source.channel.map(FileChannel.MapMode.READ_ONLY, 0, expected.baseSize)
                        }
                        decoder.startDecoding(dictionary)
                        decoder.decodeChunk(ByteBuffer.wrap(prefix), destination)
                        val window = ByteArray(MAX_ENCODED_WINDOW)
                        var written = 0L
                        while (input.filePointer < expected.patchSize) {
                            checkActive()
                            val start = input.filePointer
                            when (input.readUnsignedByte()) {
                                0 -> Unit
                                1 -> {
                                    val length = input.readLength()
                                    val position = input.readLength()
                                    require(length in 1..(expected.baseSize - position))
                                }
                                else -> error("Unsupported VCDIFF window")
                            }
                            val encodedLength = input.readLength()
                            val payload = input.filePointer
                            require(encodedLength in 1..(expected.patchSize - payload))
                            val end = payload + encodedLength
                            val windowBytes = end - start
                            require(windowBytes <= window.size)
                            val targetLength = input.readLength()
                            require(targetLength in 1..minOf(MAX_WINDOW.toLong(), expected.targetSize - written))
                            require(input.filePointer < end && input.readUnsignedByte() == 0)
                            val dataLength = input.readLength()
                            val instructionsLength = input.readLength()
                            val addressesLength = input.readLength()
                            require(dataLength + instructionsLength + addressesLength == end - input.filePointer)
                            input.seek(start)
                            input.readFully(window, 0, windowBytes.toInt())
                            decoder.decodeChunk(ByteBuffer.wrap(window, 0, windowBytes.toInt()), destination)
                            written += targetLength
                            require(destination.channel.position() == written) { "Incomplete VCDIFF window" }
                            progress(written, expected.targetSize)
                        }
                        decoder.finishDecoding()
                        require(written == expected.targetSize)
                        checkActive()
                        destination.fd.sync()
                    }
                }
            }
            require(DeltaPatch.hash(output, checkActive) == expected.targetSha256) { "Reconstructed APK hash mismatch" }
            complete = true
        } finally {
            if (!complete) output.delete()
        }
    }

    private fun RandomAccessFile.readLength(): Long {
        var value = 0L
        repeat(5) {
            val octet = readUnsignedByte()
            value = (value shl 7) or (octet and 0x7f).toLong()
            require(value <= DeltaPatch.MAX_APK_SIZE)
            if (octet and 0x80 == 0) return value
        }
        error("Invalid VCDIFF integer")
    }
}
