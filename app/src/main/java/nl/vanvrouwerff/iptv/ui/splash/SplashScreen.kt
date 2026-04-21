package nl.vanvrouwerff.iptv.ui.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

/**
 * Full-screen splash shown while the app is still resolving its initial state
 * (DataStore first emit, Room warm-up, profile lookup). Intended to paint within
 * the first frame so the user never sees a blank canvas or a flash of the
 * Welcome screen before the real route is known.
 *
 * Anatomy:
 *  - Ambient radial gradient backdrop matching the Channels screen so the swap
 *    to the real UI doesn't feel like a cut.
 *  - Logo that fades + gently eases in from a slight shrink.
 *  - "Kanalen worden geladen…" caption on a short delay so users on a genuinely
 *    fast start aren't told the app was loading at all.
 *  - Indeterminate progress pill animating a highlight across its width.
 */
@Composable
fun SplashScreen(message: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep),
    ) {
        // Ambient accent glow — identical to the Channels screen backdrop so the
        // crossfade into the real UI feels like a continuation, not a cut.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            IptvPalette.AccentDeep.copy(alpha = 0.28f),
                            IptvPalette.BackgroundDeep,
                        ),
                        radius = 1600f,
                    )
                )
        )

        // Entry animation: slight zoom-in + fade so the logo "settles" on screen
        // rather than popping. Kept short (320 ms) so on fast starts the splash
        // doesn't feel sluggish.
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val enterProgress by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            label = "splash-enter",
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .alpha(enterProgress)
                .scale(0.96f + 0.04f * enterProgress),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_iptv_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(width = 320.dp, height = 140.dp),
            )

            Spacer(Modifier.height(28.dp))

            // Caption + progress — hidden briefly so a ~instant start never shows
            // "Laden…" at all. After ~180 ms it fades in subtly.
            var captionVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(180)
                captionVisible = true
            }
            val captionAlpha by animateFloatAsState(
                targetValue = if (captionVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 240, easing = LinearEasing),
                label = "splash-caption",
            )

            Text(
                text = message ?: stringResource(R.string.splash_loading),
                style = MaterialTheme.typography.labelLarge.copy(
                    color = IptvPalette.TextSecondary,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.alpha(captionAlpha),
            )

            Spacer(Modifier.height(16.dp))

            IndeterminateProgressPill(
                modifier = Modifier.alpha(captionAlpha),
            )
        }
    }
}

/**
 * Thin pill with a highlight that sweeps left-to-right. Communicates "busy"
 * without pinning focus or hijacking the D-pad, both of which a material
 * CircularProgressIndicator would do on a TV.
 */
@Composable
private fun IndeterminateProgressPill(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "splash-progress")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shift",
    )
    val trackWidth = 180.dp
    val highlightWidth = 72.dp
    val density = LocalDensity.current
    val trackPx = with(density) { trackWidth.toPx() }
    val highlightPx = with(density) { highlightWidth.toPx() }

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(IptvPalette.SurfaceElevated),
    ) {
        // Sweep a translucent highlight left-to-right. `graphicsLayer` translation
        // avoids re-laying out the child each frame — the animation is GPU-only.
        Box(
            modifier = Modifier
                .width(highlightWidth)
                .height(4.dp)
                .graphicsLayer {
                    translationX = -highlightPx + (trackPx + highlightPx) * shift
                }
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            IptvPalette.Accent.copy(alpha = 0.0f),
                            IptvPalette.Accent,
                            IptvPalette.Accent.copy(alpha = 0.0f),
                        ),
                    ),
                ),
        )
    }
}
