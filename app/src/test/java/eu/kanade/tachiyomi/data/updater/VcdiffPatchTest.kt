package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.data.updater.delta.DeltaPatch
import eu.kanade.tachiyomi.data.updater.delta.DeltaRecord
import eu.kanade.tachiyomi.data.updater.delta.VcdiffPatch
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

class VcdiffPatchTest {
    @TempDir lateinit var directory: File

    // xdelta3 3.2.2, default RFC 3284 code table, one COPY/ADD window.
    private val encoded = Base64.getDecoder().decode("1sPEAAABhIAAABmEgAAABwgDTnlhbmltZROKOQgTg/VAAIpA")

    private fun fixture(patchBytes: ByteArray, checkResult: (File, File, File, DeltaRecord, ByteArray) -> Unit) {
        val bytes = ByteArray(65_536) { index -> (index * 37 + index / 17).toByte() }
        val targetBytes = bytes.copyOf().apply { "Nyanime".encodeToByteArray().copyInto(this, 1337) }
        val source = File(directory, "base.apk").apply { writeBytes(bytes) }
        val target = File(directory, "target.apk").apply { writeBytes(targetBytes) }
        val patch = File(directory, "update.vcdiff").apply { writeBytes(patchBytes) }
        val output = File(directory, "reconstructed.apk")
        val record = DeltaRecord(
            DeltaPatch.hash(source), source.length(), DeltaPatch.hash(target), target.length(),
            DeltaPatch.hash(patch), patch.length(), patch.name, target.name,
        )
        checkResult(source, patch, output, record, targetBytes)
    }

    @Test
    fun reconstructsAnIndependentXdeltaPatchExactly() {
        fixture(encoded) { base, patch, output, record, expected ->
            VcdiffPatch.apply(base, patch, output, record, {}, { _, _ -> })
            assertArrayEquals(expected, output.readBytes())
        }
    }

    @Test
    fun truncatedWindowLeavesNoOutput() {
        fixture(encoded.copyOf(encoded.size - 1)) { base, patch, output, record, _ ->
            assertThrows(Exception::class.java) {
                VcdiffPatch.apply(base, patch, output, record, {}, { _, _ -> })
            }
            assertFalse(output.exists())
        }
    }
}
