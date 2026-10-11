package it.lorelore.nyanime.reader.quality

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class QualityModelStoreTest {
    @TempDir lateinit var root: File
    private val bytes = "verified fixture model".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val model = QualityModel(
        QualityEngineId.REAL_CUGAN,
        "fixture-v1",
        "test-only",
        listOf(QualityModelFile("model.bin", bytes.size.toLong(), digest, "https://invalid.test/fixture")),
    )

    @Test fun `verified files publish together and identical installations do not download again`() = runBlocking {
        val store = QualityModelStore(root)
        store.install(
            model,
            QualityModelDownloader { _, file, _, progress ->
                assertFalse(store.available(model))
                file.writeBytes(bytes)
                progress(bytes.size.toLong())
            },
        ) { _, _ -> }
        assertTrue(store.available(model))
        store.install(model, QualityModelDownloader { _, _, _, _ -> error("Must reuse verified model") }) { _, _ -> }
        assertTrue(root.listFiles()!!.single().name == model.revision)
    }

    @Test fun `corrupt download cannot become available or remove another revision`() {
        val earlier = File(root, "fixture-v0").apply { mkdir() }
        File(earlier, "keep.bin").writeBytes(bytes)
        val store = QualityModelStore(root)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.install(
                    model,
                    QualityModelDownloader { _, file, _, _ ->
                        file.writeBytes(ByteArray(bytes.size))
                    },
                ) { _, _ -> }
            }
        }
        assertFalse(store.available(model))
        assertTrue(root.listFiles()!!.single() == earlier)
    }

    @Test fun `cancellation removes all partial output without publishing it`() = runBlocking {
        val store = QualityModelStore(root)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            store.install(
                model,
                QualityModelDownloader { _, file, _, _ ->
                    file.writeBytes(bytes.take(3).toByteArray())
                    started.complete(Unit)
                    awaitCancellation()
                },
            ) { _, _ -> }
        }
        started.await()
        assertFalse(store.available(model))
        job.cancelAndJoin()
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test fun `descriptor cannot escape storage or request unbounded files`() {
        assertThrows(IllegalArgumentException::class.java) { model.copy(revision = "../escape") }
        assertThrows(IllegalArgumentException::class.java) { model.files.first().copy(name = "../escape") }
        assertThrows(IllegalArgumentException::class.java) { model.files.first().copy(size = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { model.files.first().copy(sha256 = "unknown") }
    }
}
