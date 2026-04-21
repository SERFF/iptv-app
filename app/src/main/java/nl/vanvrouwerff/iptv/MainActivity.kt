package nl.vanvrouwerff.iptv

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.settings.SourceConfig
import nl.vanvrouwerff.iptv.player.PlayerActivity
import nl.vanvrouwerff.iptv.ui.channels.ChannelsScreen
import nl.vanvrouwerff.iptv.ui.detail.MovieDetailScreen
import nl.vanvrouwerff.iptv.ui.seriesdetail.Episode
import nl.vanvrouwerff.iptv.ui.seriesdetail.SeriesDetailScreen
import nl.vanvrouwerff.iptv.ui.seriesdetail.SeriesSeason
import nl.vanvrouwerff.iptv.ui.profiles.ProfilesScreen
import nl.vanvrouwerff.iptv.ui.settings.SettingsScreen
import nl.vanvrouwerff.iptv.ui.splash.SplashScreen
import nl.vanvrouwerff.iptv.ui.theme.IptvTheme
import nl.vanvrouwerff.iptv.ui.wizard.WelcomeScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IptvTheme {
                AppRoot(
                    onPlay = { channel, list, resumeMs -> openPlayer(channel, list, resumeMs) },
                    onPlayEpisode = { ep, season, resumeMs -> openPlayerForEpisode(ep, season, resumeMs) },
                    onPlayDirect = ::onPlayDirect,
                )
            }
        }
    }

    /**
     * Workaround for androidx.tv.foundation:1.0.0-alpha10 focus-search crash.
     *
     * Clicking an EpisodeRow fires startActivity() for PlayerActivity synchronously on the
     * main thread. That begins tearing down Compose nodes in the TvLazyColumn while the
     * D-pad DPAD_CENTER event is still dispatching. The focus traversal code then calls
     * `focusRect()` on a node whose LayoutCoordinates are already detached, throwing
     * `IllegalStateException: LayoutCoordinate operations are only valid when isAttached is true`
     * — which kills the process before the transition completes.
     *
     * Swallowing the exception here lets the activity transition finish cleanly; the user
     * sees PlayerActivity as intended. The proper fix is upgrading `tv-foundation` (the bug
     * is addressed in later alphas), but that needs a Compose BOM bump and broader testing.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = try {
        super.dispatchKeyEvent(event)
    } catch (e: IllegalStateException) {
        if (e.message?.contains("isAttached", ignoreCase = true) == true) {
            Log.w(TAG, "Suppressed Compose focus-search crash during key dispatch", e)
            true
        } else {
            throw e
        }
    }

    private fun openPlayer(channel: Channel, all: List<Channel>, resumeMs: Long) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            putExtra(PlayerActivity.EXTRA_CHANNEL_IDS, all.map { it.id }.toTypedArray())
            if (resumeMs > 0L) putExtra(PlayerActivity.EXTRA_RESUME_POSITION_MS, resumeMs)
        }
        startActivity(intent)
    }

    private fun openPlayerForEpisode(episode: Episode, season: SeriesSeason, resumeMs: Long) {
        val orderedEpisodes = season.episodes
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, episode.id)
            putExtra(PlayerActivity.EXTRA_ADHOC_IDS, orderedEpisodes.map { it.id }.toTypedArray())
            putExtra(PlayerActivity.EXTRA_ADHOC_URLS, orderedEpisodes.map { it.streamUrl }.toTypedArray())
            putExtra(PlayerActivity.EXTRA_ADHOC_NAMES, orderedEpisodes.map { it.title }.toTypedArray())
            putExtra(PlayerActivity.EXTRA_ADHOC_TYPE, ContentType.SERIES.name)
            if (resumeMs > 0L) putExtra(PlayerActivity.EXTRA_RESUME_POSITION_MS, resumeMs)
        }
        startActivity(intent)
    }

    /**
     * "Continue watching" click: look up saved progress, then launch the player directly,
     * routing ad-hoc extras for episodes (not in Room) vs. normal extras for movies.
     */
    private fun onPlayDirect(channel: Channel) {
        lifecycleScope.launch {
            val app = IptvApp.get()
            val dao = app.database.channelDao()
            val resumeMs = dao.getProgress(app.activeProfileId.value, channel.id)?.positionMs ?: 0L
            val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                if (channel.id.startsWith("xt-episode:")) {
                    // Ad-hoc path — episode isn't in channels table.
                    val url = channel.streamUrl ?: return@apply
                    putExtra(PlayerActivity.EXTRA_ADHOC_IDS, arrayOf(channel.id))
                    putExtra(PlayerActivity.EXTRA_ADHOC_URLS, arrayOf(url))
                    putExtra(PlayerActivity.EXTRA_ADHOC_NAMES, arrayOf(channel.name))
                    putExtra(PlayerActivity.EXTRA_ADHOC_TYPE, ContentType.SERIES.name)
                } else {
                    putExtra(PlayerActivity.EXTRA_CHANNEL_IDS, arrayOf(channel.id))
                }
                if (resumeMs > 0L) putExtra(PlayerActivity.EXTRA_RESUME_POSITION_MS, resumeMs)
            }
            startActivity(intent)
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

private sealed interface Route {
    data object Welcome : Route
    data object Channels : Route
    data object Settings : Route
    data object Profiles : Route
    data class MovieDetail(val channelId: String) : Route
    data class SeriesDetail(val seriesId: String) : Route
}

@Composable
private fun AppRoot(
    onPlay: (Channel, List<Channel>, Long) -> Unit,
    onPlayEpisode: (Episode, SeriesSeason, Long) -> Unit,
    onPlayDirect: (Channel) -> Unit,
) {
    val app = IptvApp.get()
    // Sentinel wrapper for "first emit hasn't arrived yet" vs "emit arrived but null".
    // Without this the very first frame routes to Welcome even for users with a valid
    // source, producing a visible flash. `null` on the outer Optional = unresolved.
    val sourceSlot by app.settings.sourceConfig
        .collectAsState(initial = UNRESOLVED_SOURCE)
    val resolved = sourceSlot !== UNRESOLVED_SOURCE
    val source = if (resolved) sourceSlot as SourceConfig? else null

    // Keep a short minimum so the splash doesn't flash-and-vanish on warm starts where
    // DataStore resolves in <100ms, but don't steal visible time from the user every
    // launch. 180ms reads as a soft fade rather than a branding pause.
    var minSplashElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(MIN_SPLASH_DURATION_MS)
        minSplashElapsed = true
    }
    val showSplash = !resolved || !minSplashElapsed

    var routeOverride by remember { mutableStateOf<Route?>(null) }

    // Single active route — detail screens REPLACE the ChannelsScreen in the composition
    // tree rather than overlaying on top of it. Overlaying kept ChannelsScreen focusable
    // underneath, which caused D-pad events inside a detail screen to leak through to the
    // series/movie cards beneath, randomly opening another detail view. ChannelsViewModel
    // is activity-scoped so its per-type cache survives this swap; only LazyListState
    // (scroll position) resets on back, which is a fair trade for correctness.
    val route: Route = routeOverride
        ?: if (source == null) Route.Welcome else Route.Channels

    // Crossfade between splash and the real app so the swap is a smooth dissolve rather
    // than a pop. `targetState = showSplash` keys on a Boolean so Compose only transitions
    // once per state flip, not on every recomposition.
    AnimatedContent(
        targetState = showSplash,
        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(260)) },
        label = "splash-swap",
        modifier = Modifier.fillMaxSize(),
    ) { splash ->
        if (splash) {
            SplashScreen()
        } else {
            AppRouteHost(
                route = route,
                source = source,
                onRouteChange = { routeOverride = it },
                onPlay = onPlay,
                onPlayEpisode = onPlayEpisode,
                onPlayDirect = onPlayDirect,
            )
        }
    }
}

/** Sentinel distinguishing "no source configured" from "DataStore hasn't emitted yet". */
private val UNRESOLVED_SOURCE = Any()

private const val MIN_SPLASH_DURATION_MS: Long = 180L

@Composable
private fun AppRouteHost(
    route: Route,
    source: SourceConfig?,
    onRouteChange: (Route?) -> Unit,
    onPlay: (Channel, List<Channel>, Long) -> Unit,
    onPlayEpisode: (Episode, SeriesSeason, Long) -> Unit,
    onPlayDirect: (Channel) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (route) {
            Route.Welcome -> WelcomeScreen(onConfigure = { onRouteChange(Route.Settings) })
            Route.Settings -> SettingsScreen(
                onSaved = { onRouteChange(Route.Channels) },
                onBack = { onRouteChange(if (source == null) Route.Welcome else Route.Channels) },
                onOpenProfiles = { onRouteChange(Route.Profiles) },
            )
            Route.Profiles -> ProfilesScreen(
                onBack = { onRouteChange(Route.Channels) },
                onPicked = { onRouteChange(Route.Channels) },
            )
            Route.Channels -> ChannelsScreen(
                onOpenSettings = { onRouteChange(Route.Settings) },
                onOpenProfiles = { onRouteChange(Route.Profiles) },
                onPlay = { channel, list ->
                    when (channel.type) {
                        ContentType.MOVIE -> onRouteChange(Route.MovieDetail(channel.id))
                        ContentType.SERIES -> {
                            val raw = channel.id.removePrefix("xt-series:")
                            onRouteChange(Route.SeriesDetail(raw))
                        }
                        ContentType.TV -> onPlay(channel, list, 0L)
                    }
                },
                onPlayDirect = onPlayDirect,
            )
            is Route.MovieDetail -> MovieDetailScreen(
                channelId = route.channelId,
                onBack = { onRouteChange(Route.Channels) },
                onPlay = { channel, resumeMs -> onPlay(channel, listOf(channel), resumeMs) },
                onPickRelated = { channel ->
                    when (channel.type) {
                        ContentType.MOVIE -> onRouteChange(Route.MovieDetail(channel.id))
                        ContentType.SERIES ->
                            onRouteChange(Route.SeriesDetail(channel.id.removePrefix("xt-series:")))
                        ContentType.TV -> Unit // no-op: TV isn't shown in VOD related rails
                    }
                },
            )
            is Route.SeriesDetail -> SeriesDetailScreen(
                seriesId = route.seriesId,
                onBack = { onRouteChange(Route.Channels) },
                onPlayEpisode = onPlayEpisode,
            )
        }
    }
}
