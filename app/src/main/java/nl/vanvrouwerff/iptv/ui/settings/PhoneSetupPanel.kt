package nl.vanvrouwerff.iptv.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.settings.PhoneSetupServer
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

/**
 * QR code + URL for filling in the source on a phone. Starts the LAN form server while on
 * screen and stops it again when the panel leaves the composition.
 */
@Composable
fun PhoneSetupPanel(qrSize: Dp = 150.dp, modifier: Modifier = Modifier) {
    var url by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        url = PhoneSetupServer.start()
        onDispose { PhoneSetupServer.stop() }
    }
    val qr = remember(url) { url?.let { encodeQr(it) } }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = stringResource(R.string.phone_setup_title),
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .size(qrSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .padding(8.dp),
            )
            Spacer(Modifier.width(20.dp))
        }
        Column {
            Text(
                text = stringResource(R.string.phone_setup_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = IptvPalette.TextPrimary,
            )
            Text(
                text = if (url != null) stringResource(R.string.phone_setup_body) else stringResource(R.string.phone_setup_offline),
                style = MaterialTheme.typography.bodySmall,
                color = IptvPalette.TextSecondary,
                modifier = Modifier.width(360.dp),
            )
            url?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = IptvPalette.AccentSoft,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun encodeQr(text: String): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 0),
    )
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bitmap.asImageBitmap()
}.getOrNull()
