package nl.vanvrouwerff.iptv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

object IptvPalette {
    val BackgroundDeep = Color(0xFF0A0A0C)
    val BackgroundLift = Color(0xFF141418)
    val SurfaceLift = Color(0xFF1D1F26)
    val SurfaceElevated = Color(0xFF262933)
    val Accent = Color(0xFFFF3B5C)
    val AccentSoft = Color(0xFFFF7A92)
    val AccentDeep = Color(0xFF9E0031)
    val TextPrimary = Color(0xFFF4F4F6)
    val TextSecondary = Color(0xFFB4B7C0)
    // Lifted from #7A7E8A (~4.0:1 contrast on BackgroundDeep, WCAG AA borderline at 3m)
    // to #9AA0AC (~6.4:1) so metadata lines in detail screens stay legible from the couch.
    val TextTertiary = Color(0xFF9AA0AC)
}

private val Scheme = darkColorScheme(
    primary = IptvPalette.Accent,
    onPrimary = Color.White,
    primaryContainer = IptvPalette.AccentDeep,
    onPrimaryContainer = IptvPalette.AccentSoft,
    secondary = IptvPalette.AccentSoft,
    onSecondary = Color.White,
    surface = IptvPalette.SurfaceLift,
    onSurface = IptvPalette.TextPrimary,
    surfaceVariant = IptvPalette.SurfaceElevated,
    onSurfaceVariant = IptvPalette.TextSecondary,
    background = IptvPalette.BackgroundDeep,
    onBackground = IptvPalette.TextPrimary,
    border = Color(0x22FFFFFF),
    borderVariant = Color(0x11FFFFFF),
)

// TV-material3 defaults target ~2m viewing; the Formuler sits ~3m from a couch. A 10–15 %
// bump on body / label sizes reads comfortably without overflowing any fixed-size cards.
// Headlines are already large enough — leaving them at defaults avoids hero/rails reflows.
private val TvTypography = Typography(
    bodySmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

// OutlinedTextField (and other compose-material3 widgets) read from the compose-material3
// theme — not the tv-material3 theme we use elsewhere. Without this mirror the text field
// falls back to the default light scheme and input text is nearly invisible on our dark
// background. Mirroring onSurface → white keeps every current and future text input
// readable without per-call-site overrides.
private val M3Scheme = m3DarkColorScheme(
    primary = IptvPalette.Accent,
    onPrimary = Color.White,
    surface = IptvPalette.SurfaceLift,
    onSurface = Color.White,
    surfaceVariant = IptvPalette.SurfaceElevated,
    onSurfaceVariant = IptvPalette.TextSecondary,
    background = IptvPalette.BackgroundDeep,
    onBackground = Color.White,
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = TvTypography) {
        M3MaterialTheme(colorScheme = M3Scheme, content = content)
    }
}
