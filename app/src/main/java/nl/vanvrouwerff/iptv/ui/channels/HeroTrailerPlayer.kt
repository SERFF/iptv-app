package nl.vanvrouwerff.iptv.ui.channels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Renders a muted YouTube trailer inline as a Compose background using the IFrame Player
 * inside a WebView. Used by the hero carousel: after a short idle window we crossfade from
 * the Ken Burns backdrop to this player.
 *
 * Failures (no network, blocked WebView, invalid key) call [onError] so the carousel can
 * fall back to its static backdrop without crashing.
 *
 * The player is released on Compose disposal — re-rendering the carousel with a new key
 * tears down the previous PlayerView so audio never leaks between rotations.
 */
@Composable
fun HeroTrailerPlayer(
    youtubeKey: String,
    modifier: Modifier = Modifier,
    muted: Boolean = true,
    onError: () -> Unit = {},
) {
    var ytPlayer by remember(youtubeKey) { mutableStateOf<YouTubePlayer?>(null) }
    LaunchedEffect(muted, ytPlayer) {
        val p = ytPlayer ?: return@LaunchedEffect
        runCatching {
            if (muted) {
                p.setVolume(0)
                p.mute()
            } else {
                p.unMute()
                p.setVolume(100)
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var playerViewRef by remember(youtubeKey) { mutableStateOf<YouTubePlayerView?>(null) }

    AndroidView(
        factory = { ctx ->
            val options = IFramePlayerOptions.Builder()
                .controls(0)
                .rel(0)
                .ivLoadPolicy(3)
                .ccLoadPolicy(0)
                .build()
            YouTubePlayerView(ctx).apply {
                enableAutomaticInitialization = false
                lifecycleOwner.lifecycle.addObserver(this)
                initialize(object : AbstractYouTubePlayerListener() {
                    override fun onReady(player: YouTubePlayer) {
                        runCatching {
                            player.setVolume(0)
                            player.loadVideo(youtubeKey, 0f)
                        }.onFailure { onError() }
                        ytPlayer = player
                    }

                    override fun onError(
                        youTubePlayer: YouTubePlayer,
                        error: PlayerConstants.PlayerError,
                    ) {
                        onError()
                    }
                }, options)
                playerViewRef = this
            }
        },
        modifier = modifier,
    )

    DisposableEffect(youtubeKey) {
        onDispose {
            playerViewRef?.let {
                lifecycleOwner.lifecycle.removeObserver(it)
                it.release()
            }
            playerViewRef = null
        }
    }
}
