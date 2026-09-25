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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.settings.SourceConfig
import nl.vanvrouwerff.iptv.player.PlayerActivity
import nl.vanvrouwerff.iptv.ui.categories.CategoriesScreen
import nl.vanvrouwerff.iptv.ui.guide.GuideScreen
import nl.vanvrouwerff.iptv.ui.channels.ChannelsScreen
import nl.vanvrouwerff.iptv.ui.detail.MovieDetailScreen
import nl.vanvrouwerff.iptv.ui.seriesdetail.Episode
import nl.vanvrouwerff.iptv.ui.seriesdetail.SeriesDetailScreen
import nl.vanvrouwerff.iptv.ui.seriesdetail.SeriesRef
import nl.vanvrouwerff.iptv.ui.seriesdetail.SeriesSeason
import nl.vanvrouwerff.iptv.ui.profilepicker.ProfilePickerScreen
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
                    onPlayEpisode = { ep, season, series, resumeMs -> openPlayerForEpisode(ep, season, series, resumeMs) },
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
            // Tens of thousands of ids blow the 1 MB binder limit; let the player query instead.
            if (all.size > PlayerActivity.MAX_INTENT_IDS) {
                putExtra(PlayerActivity.EXTRA_SCOPE_TYPE, channel.type.name)
            } else {
                putExtra(PlayerActivity.EXTRA_CHANNEL_IDS, all.map { it.id }.toTypedArray())
            }
            if (resumeMs > 0L) putExtra(PlayerActivity.EXTRA_RESUME_POSITION_MS, resumeMs)
        }
        startActivity(intent)
    }

    private fun openPlayerForEpisode(episode: Episode, season: SeriesSeason, series: SeriesRef, resumeMs: Long) {
        val orderedEpisodes = season.episodes
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_SERIES_CHANNEL_ID, series.channelId)
            putExtra(PlayerActivity.EXTRA_SERIES_NAME, series.name)
            putExtra(PlayerActivity.EXTRA_SERIES_COVER, series.cover)
            putExtra(PlayerActivity.EXTRA_SERIES_SEASON, season.number)
            putExtra(PlayerActivity.EXTRA_SERIES_EPISODE_NUMBERS, orderedEpisodes.map { it.episodeNumber }.toIntArray())
            putExtra(PlayerActivity.EXTRA_SERIES_EPISODE_COVERS, orderedEpisodes.map { it.coverUrl.orEmpty() }.toTypedArray())
            putExtra(PlayerActivity.EXTRA_SERIES_EPISODE_DURATIONS, orderedEpisodes.map { it.durationSecs }.toLongArray())
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
    data object ProfilePicker : Route
    data class MovieDetail(val channelId: String, val preview: Channel? = null) : Route
    data class SeriesDetail(val seriesId: String, val preview: Channel? = null) : Route
    data class Categories(val type: ContentType, val category: String?) : Route
    data object Guide : Route
}

/** Skip the cold-start profile picker if the user picked a profile within this window. */
private const val PROFILE_SESSION_WINDOW_MS: Long = 8L * 3600 * 1000

@Composable
private fun AppRoot(
    onPlay: (Channel, List<Channel>, Long) -> Unit,
    onPlayEpisode: (Episode, SeriesSeason, SeriesRef, Long) -> Unit,
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

    // Cold-start profile-picker gate: only show the picker on a fresh launch when there
    // is more than one profile AND the user hasn't picked one within the session window.
    // null = unresolved (suspended query hasn't returned); the splash is held until then
    // so we don't flash Channels and then yank into the picker.
    val profilePickerNeeded: Boolean? by produceState<Boolean?>(initialValue = null, source) {
        if (source == null) {
            value = false
            return@produceState
        }
        value = runCatching {
            val count = app.database.profileDao().allProfiles().size
            val lastSession = app.settings.lastProfileSessionAt.first()
            count > 1 && (System.currentTimeMillis() - lastSession) > PROFILE_SESSION_WINDOW_MS
        }.getOrDefault(false)
    }

    val showSplash = !resolved || !minSplashElapsed || (source != null && profilePickerNeeded == null)

    // Simple back stack on top of the computed root route (Welcome / ProfilePicker /
    // Channels), so BACK from a related title returns to the previous detail screen.
    val backStack = remember { mutableStateListOf<Route>() }
    var pickerDismissed by remember { mutableStateOf(false) }

    // Single active route — detail screens REPLACE the ChannelsScreen in the composition
    // tree rather than overlaying on top of it. Overlaying kept ChannelsScreen focusable
    // underneath, which caused D-pad events inside a detail screen to leak through to the
    // series/movie cards beneath, randomly opening another detail view. ChannelsViewModel
    // is activity-scoped so its per-type cache survives this swap; only LazyListState
    // (scroll position) resets on back, which is a fair trade for correctness.
    val route: Route = backStack.lastOrNull()
        ?: when {
            source == null -> Route.Welcome
            profilePickerNeeded == true && !pickerDismissed -> Route.ProfilePicker
            else -> Route.Channels
        }

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
                onNavigate = { next ->
                    if (next == Route.Channels) {
                        pickerDismissed = true
                        backStack.clear()
                    } else {
                        backStack.add(next)
                    }
                },
                onBack = { if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) },
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
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
    onPlay: (Channel, List<Channel>, Long) -> Unit,
    onPlayEpisode: (Episode, SeriesSeason, SeriesRef, Long) -> Unit,
    onPlayDirect: (Channel) -> Unit,
) {
    // Keyed on the route so two consecutive screens of the same kind (film → related film)
    // don't share remembered state such as the initial focus request.
    Box(modifier = Modifier.fillMaxSize()) { key(route) {
        when (route) {
            Route.Welcome -> WelcomeScreen(onConfigure = { onNavigate(Route.Settings) })
            Route.Settings -> SettingsScreen(
                onSaved = { onNavigate(Route.Channels) },
                onBack = onBack,
                onOpenProfiles = { onNavigate(Route.Profiles) },
            )
            Route.Profiles -> ProfilesScreen(
                onBack = onBack,
                onPicked = { onNavigate(Route.Channels) },
            )
            Route.ProfilePicker -> ProfilePickerScreen(
                onPicked = { onNavigate(Route.Channels) },
                onManageProfiles = { onNavigate(Route.Profiles) },
            )
            Route.Channels -> ChannelsScreen(
                onOpenSettings = { onNavigate(Route.Settings) },
                onOpenCategories = { type, category -> onNavigate(Route.Categories(type, category)) },
                onOpenGuide = { onNavigate(Route.Guide) },
                onOpenProfiles = { onNavigate(Route.Profiles) },
                onPlay = { channel, list ->
                    when (channel.type) {
                        ContentType.MOVIE -> onNavigate(Route.MovieDetail(channel.id, channel))
                        ContentType.SERIES -> {
                            val raw = channel.id.removePrefix("xt-series:")
                            onNavigate(Route.SeriesDetail(raw, channel))
                        }
                        ContentType.TV -> onPlay(channel, list, 0L)
                    }
                },
                onPlayDirect = onPlayDirect,
                onOpenDetail = { channel ->
                    when (channel.type) {
                        ContentType.MOVIE -> onNavigate(Route.MovieDetail(channel.id, channel))
                        ContentType.SERIES -> {
                            val raw = channel.id.removePrefix("xt-series:")
                            onNavigate(Route.SeriesDetail(raw, channel))
                        }
                        ContentType.TV -> Unit
                    }
                },
            )
            is Route.MovieDetail -> MovieDetailScreen(
                channelId = route.channelId,
                preview = route.preview,
                onBack = onBack,
                onPlay = { channel, resumeMs -> onPlay(channel, listOf(channel), resumeMs) },
                onPickRelated = { channel ->
                    when (channel.type) {
                        ContentType.MOVIE -> onNavigate(Route.MovieDetail(channel.id, channel))
                        ContentType.SERIES ->
                            onNavigate(Route.SeriesDetail(channel.id.removePrefix("xt-series:"), channel))
                        ContentType.TV -> Unit // no-op: TV isn't shown in VOD related rails
                    }
                },
            )
            Route.Guide -> GuideScreen(
                onBack = onBack,
                onPlay = { channel, list -> onPlay(channel, list, 0L) },
            )
            is Route.Categories -> CategoriesScreen(
                type = route.type,
                initialCategory = route.category,
                onBack = onBack,
                onOpen = { channel, list ->
                    when (channel.type) {
                        ContentType.MOVIE -> onNavigate(Route.MovieDetail(channel.id, channel))
                        ContentType.SERIES ->
                            onNavigate(Route.SeriesDetail(channel.id.removePrefix("xt-series:"), channel))
                        ContentType.TV -> onPlay(channel, list, 0L)
                    }
                },
            )
            is Route.SeriesDetail -> SeriesDetailScreen(
                seriesId = route.seriesId,
                preview = route.preview,
                onBack = onBack,
                onPlayEpisode = onPlayEpisode,
            )
        }
    } }
}
