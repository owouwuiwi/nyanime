package eu.kanade.tachiyomi.data.download.transfer

import eu.kanade.tachiyomi.data.download.anime.VideoStorageException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okio.Throttler
import okio.buffer
import java.io.IOException
import java.io.OutputStream

/** The response provider remains the extension's getImage(), including custom methods and data saver. */
class MangaImageTransfer(private val sourceId: Long, private val throttler: Throttler) {
    suspend fun download(
        response: suspend () -> Response,
        output: () -> OutputStream,
        availableBytes: () -> Long,
        bytesReceived: (Long) -> Unit,
        finalized: (Response) -> Unit,
    ) = TransferCoordinator.transfer(sourceId) {
        response().use { response ->
            coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        response.close()
                    }
                }
                try {
                    var bytes = 0L
                    var lastSpaceCheck = -8L * 1024 * 1024
                    throttler.source(response.body.source()).buffer().use { input ->
                        val destination = try {
                            output()
                        } catch (
                            error: IOException,
                        ) {
                            throw VideoStorageException("Cartella download non accessibile.")
                        }
                        destination.use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (bytes - lastSpaceCheck >= 8L * 1024 * 1024) {
                                    lastSpaceCheck = bytes
                                    if (availableBytes() in
                                        0 until 32L * 1024 * 1024
                                    ) {
                                        throw VideoStorageException(
                                            "Spazio insufficiente per completare le pagine.",
                                        )
                                    }
                                }
                                try {
                                    output.write(buffer, 0, count)
                                } catch (
                                    error: IOException,
                                ) {
                                    throw VideoStorageException("Impossibile salvare la pagina.")
                                }
                                bytes += count
                                bytesReceived(count.toLong())
                                TransferCoordinator.bytes(sourceId, count.toLong())
                            }
                        }
                    }
                    if (bytes <= 0 ||
                        (response.body.contentLength() >= 0 && bytes != response.body.contentLength())
                    ) {
                        throw IOException("Immagine incompleta.")
                    }
                    finalized(response)
                } finally {
                    cancellation.cancel()
                }
            }
        }
    }
}
