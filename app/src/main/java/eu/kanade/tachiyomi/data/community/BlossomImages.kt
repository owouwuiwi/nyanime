package eu.kanade.tachiyomi.data.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Re-encoding removes EXIF/GPS. A failed upload leaves the local draft untouched. */
internal object BlossomImages {
    val hosts = listOf("https://blossom.primal.net", "https://blossom.band")
    suspend fun prepare(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = if (Build.VERSION.SDK_INT >=
            28
        ) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val ratio = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(
                    maxOf(1, (info.size.width * ratio).toInt()),
                    maxOf(1, (info.size.height * ratio).toInt()),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            require(options.outWidth > 0 && options.outHeight > 0)
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
            requireNotNull(
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                },
            )
        }
        try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                output.toByteArray().also { require(it.size <= 2_000_000) }
            }
        } finally {
            bitmap.recycle()
        }
    }
    suspend fun upload(identity: CommunityIdentity, bytes: ByteArray, host: String): String =
        BlossomUploadClient(diagnostic = { android.util.Log.i("NyanimeImages", it) }).upload(identity, bytes, host)
}

internal fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (read < 0) return output.toByteArray()
        output.write(buffer, 0, read)
        require(output.size() <= limit) { "Il file supera la dimensione consentita" }
    }
}
