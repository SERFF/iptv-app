package nl.vanvrouwerff.iptv.ui.parental

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.ui.theme.FocusStyle
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette
import nl.vanvrouwerff.iptv.ui.theme.tvFocus

const val PIN_LENGTH = 4

/**
 * Four-digit PIN entry: an on-screen keypad plus the remote's number keys. Calls
 * [onComplete] once four digits are entered; the caller shows [error] and the pad clears.
 */
@Composable
fun PinPad(
    title: String,
    error: String?,
    onComplete: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var digits by remember { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
    LaunchedEffect(error) { if (error != null) digits = "" }
    BackHandler(onBack = onCancel)

    fun type(d: Char) {
        if (digits.length >= PIN_LENGTH) return
        digits += d
        if (digits.length == PIN_LENGTH) {
            val pin = digits
            digits = ""
            onComplete(pin)
        }
    }
    fun erase() {
        digits = digits.dropLast(1)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep.copy(alpha = 0.92f))
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                when {
                    code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        type('0' + (code - KeyEvent.KEYCODE_0)); true
                    }
                    code == KeyEvent.KEYCODE_DEL -> { erase(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = IptvPalette.TextPrimary,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(PIN_LENGTH) { i ->
                    Box(
                        Modifier
                            .size(18.dp)
                            .background(if (i < digits.length) IptvPalette.Accent else Color.Transparent, CircleShape)
                            .border(2.dp, IptvPalette.TextSecondary, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = error ?: " ",
                style = MaterialTheme.typography.bodyMedium,
                color = IptvPalette.AccentSoft,
            )
            Spacer(Modifier.height(10.dp))
            val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf(null, '0', '⌫'))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { c, key ->
                            if (key == null) {
                                Spacer(Modifier.size(72.dp))
                            } else {
                                PadKey(
                                    label = key.toString(),
                                    modifier = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier,
                                    onClick = { if (key == '⌫') erase() else type(key) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PadKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = FocusStyle.Fill,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .size(72.dp)
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape, FocusStyle.ChipScale),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = label, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        }
    }
}
