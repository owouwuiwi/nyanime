package eu.kanade.tachiyomi.ui.cloud

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CloudLoginQr(link: String, description: String = stringResource(R.string.cloud_qr_login)) {
    val image by produceState<ImageBitmap?>(null, link) {
        value = withContext(Dispatchers.Default) {
            if (link.isBlank() || link.length > 4096) return@withContext null
            val matrix =
                runCatching { QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 480, 480) }.getOrNull()
                    ?: return@withContext null
            val pixels =
                IntArray(480 * 480) { index ->
                    if (matrix[index % 480, index / 480]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            Bitmap.createBitmap(pixels, 480, 480, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    Box(
        Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, description, Modifier.fillMaxWidth().aspectRatio(1f)) }
    }
}
