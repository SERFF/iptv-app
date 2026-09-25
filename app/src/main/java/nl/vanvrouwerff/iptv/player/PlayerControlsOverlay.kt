package nl.vanvrouwerff.iptv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

/** What the playback controls show about the current item. */
data class ControlsUi(
    val title: String,
    /** "S2:A5 · Episode title" for series, group/category for others. */
    val subtitle: String?,
    val isLive: Boolean,
    val hasNextEpisode: Boolean,
    val hasPreviousChannel: Boolean = false,
    val canStartOver: Boolean = false,
    /** Bumped by the activity to pull focus back to the timebar (e.g. after a seek key). */
    val focusToken: Int,
)

/**
 * App-styled playback controls replacing Media3's default controller: title, a focusable
 * timebar (◀▶ seek, OK play/pause), elapsed and remaining time, and quick actions.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerControlsOverlay(
    ui: ControlsUi,
    playerProvider: () -> ExoPlayer?,
    onPlayPause: () -> Unit,
    onSeekBy: (deltaMs: Long) -> Unit,
    onOpenTracks: () -> Unit,
    onFromStart: () -> Unit,
    onNextEpisode: () -> Unit,
    onPreviousChannel: () -> Unit,
    onStartOver: () -> Unit,
    onInteraction: () -> Unit,
) {
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            playerProvider()?.let { p ->
                positionMs = p.currentPosition.coerceAtLeast(0L)
                durationMs = p.duration.takeIf { it > 0 } ?: 0L
                bufferedMs = p.bufferedPosition.coerceAtLeast(0L)
                playing = p.isPlaying
            }
            kotlinx.coroutines.delay(POLL_MS)
        }
    }

    val timebarFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(ui.focusToken) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching {
            if (ui.isLive || durationMs <= 0L) playFocus.requestFocus() else timebarFocus.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f),
                    0.25f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.85f),
                ),
            ),
    ) {
        Column(modifier = Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 32.dp, end = 200.dp)) {
            Text(
                text = ui.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ui.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = IptvPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 28.dp)
                .onPreviewKeyEvent { onInteraction(); false },
        ) {
            if (!ui.isLive && durationMs > 0L) {
                Timebar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    modifier = Modifier.focusRequester(timebarFocus),
                    onPlayPause = onPlayPause,
                    onSeekBy = onSeekBy,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(
                        text = "${formatClock(positionMs)} / ${formatClock(durationMs)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.player_remaining, formatRemaining(durationMs - positionMs)),
                        style = MaterialTheme.typography.labelLarge,
                        color = IptvPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.height(14.dp))
            } else if (ui.isLive) {
                Text(
                    text = stringResource(R.string.player_live),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = IptvPalette.Accent,
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ControlButton(
                    icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                    onClick = onPlayPause,
                    modifier = Modifier.focusRequester(playFocus),
                )
                ControlButton(
                    icon = Icons.Filled.Subtitles,
                    label = stringResource(R.string.player_tracks),
                    onClick = onOpenTracks,
                )
                if (!ui.isLive) {
                    ControlButton(
                        icon = Icons.Filled.Replay,
                        label = stringResource(R.string.detail_play_from_start),
                        onClick = onFromStart,
                    )
                }
                if (ui.canStartOver) {
                    ControlButton(
                        icon = Icons.Filled.Replay,
                        label = stringResource(R.string.player_start_over),
                        onClick = onStartOver,
                    )
                }
                if (ui.hasPreviousChannel) {
                    ControlButton(
                        icon = Icons.Filled.SwapHoriz,
                        label = stringResource(R.string.player_previous_channel),
                        onClick = onPreviousChannel,
                    )
                }
                if (ui.hasNextEpisode) {
                    ControlButton(
                        icon = Icons.Filled.SkipNext,
                        label = stringResource(R.string.player_next_episode),
                        onClick = onNextEpisode,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(if (ui.isLive) R.string.player_controls_hint_live else R.string.player_controls_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = IptvPalette.TextSecondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Timebar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    modifier: Modifier,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onPlayPause,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                val repeat = event.nativeKeyEvent.repeatCount
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { onSeekBy(-seekStepMs(repeat)); true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { onSeekBy(seekStepMs(repeat)); true }
                    else -> false
                }
            },
    ) {
        val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val buffered = (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(if (focused) 8.dp else 4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(buffered)
                    .height(if (focused) 8.dp else 4.dp)
                    .background(Color.White.copy(alpha = 0.35f)),
            )
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(if (focused) 8.dp else 4.dp)
                    .background(IptvPalette.Accent),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = IptvPalette.BackgroundDeep,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = label, maxLines = 1)
    }
}

/** 10 s per press, accelerating to 30 s and 60 s while the key is held. */
internal fun seekStepMs(repeatCount: Int): Long = when {
    repeatCount < 5 -> 10_000L
    repeatCount < 15 -> 30_000L
    else -> 60_000L
}

internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

internal fun formatRemaining(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}u ${m}m" else "${m}m"
}

private const val POLL_MS = 500L
