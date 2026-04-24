package nl.vanvrouwerff.iptv.player

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

@OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    playerProvider: () -> ExoPlayer?,
    aspectMode: AspectMode,
    banner: BannerInfo?,
    numericInput: String,
    errorState: ErrorState?,
    tracksOverlayVisible: Boolean,
    tracksSnapshot: TracksSnapshot,
    controllerVisible: Boolean,
    subtitleDelayMs: Long,
    displayedCues: List<Cue>,
    statsOverlayVisible: Boolean,
    statsSnapshot: StatsSnapshot?,
    nextEpisode: NextEpisodeInfo?,
    onPlayerViewReady: (PlayerView) -> Unit,
    onDismissTracks: () -> Unit,
    onDismissStats: () -> Unit,
    onSelectAspect: (AspectMode) -> Unit,
    onSelectAudio: (trackIndex: Int, groupIndex: Int) -> Unit,
    onSelectSubtitle: (trackIndex: Int?, groupIndex: Int?) -> Unit,
    onChangeSubtitleDelay: (Long) -> Unit,
    onRetryStream: () -> Unit,
    onSkipError: () -> Unit,
    onExitOnError: () -> Unit,
    onPlayNextEpisodeNow: () -> Unit,
    onCancelNextEpisode: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerAutoShow = false
                    // Give the controller a few seconds before it auto-hides on pause;
                    // under D-pad navigation that's more forgiving than the 3s default.
                    controllerShowTimeoutMs = 5_000
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowSubtitleButton(true)
                    setShowShuffleButton(false)
                    // We render subtitles ourselves (see the SubtitleView below) so we can
                    // apply a user-controlled delay. Hide Media3's internal SubtitleView so
                    // cues aren't drawn twice.
                    subtitleView?.visibility = android.view.View.INVISIBLE
                    // Focusable so it can receive D-pad events directed at the controller
                    // (seek, pause, etc). We don't request focus immediately — the Activity
                    // forwards the first OK press, which in turn focuses a controller button.
                    isFocusable = true
                    isFocusableInTouchMode = true
                    descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    resizeMode = aspectResizeMode(aspectMode)
                    player = playerProvider()
                    // Style the default timebar to match the app's red accent and replace
                    // the auto-derived keyTimeIncrement — on a 2h movie Media3 defaults to
                    // duration/20 (≈ 6 min) per DPAD press which makes fine scrubbing
                    // impossible. 10s per press + holding accelerates naturally via
                    // repeated key events.
                    findViewById<androidx.media3.ui.DefaultTimeBar>(
                        androidx.media3.ui.R.id.exo_progress,
                    )?.apply {
                        setKeyTimeIncrement(10_000L)
                        setPlayedColor(IptvPalette.Accent.toArgb())
                        setScrubberColor(IptvPalette.Accent.toArgb())
                        setBufferedColor(IptvPalette.AccentSoft.copy(alpha = 0.55f).toArgb())
                        setUnplayedColor(Color.White.copy(alpha = 0.28f).toArgb())
                    }
                    onPlayerViewReady(this)
                }
            },
            update = { view ->
                view.resizeMode = aspectResizeMode(aspectMode)
                if (view.player == null) view.player = playerProvider()
                // When a Compose overlay panel opens, release focus from the PlayerView so
                // the panel's first focusable Surface can grab it — otherwise D-pad input
                // keeps hitting the Activity's onKeyDown and never reaches the panel.
                val panelOpen = tracksOverlayVisible
                view.isFocusable = !panelOpen
                view.isFocusableInTouchMode = !panelOpen
                view.descendantFocusability = if (panelOpen) {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Our own subtitle layer — the same Media3 SubtitleView used internally by
        // PlayerView, but fed with our delayed cue stream instead. Uses the user's default
        // captioning style so the appearance matches the OS preferences.
        AndroidView(
            factory = { ctx ->
                SubtitleView(ctx).apply {
                    setUserDefaultStyle()
                    setUserDefaultTextSize()
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view -> view.setCues(displayedCues) },
            modifier = Modifier.fillMaxSize(),
        )

        // Top-left: channel info banner.
        AnimatedVisibility(
            visible = banner != null,
            enter = fadeIn(tween(180)) + slideInVertically(tween(200)) { -it / 3 },
            exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            banner?.let { InfoBanner(it) }
        }

        // Top-right clock — shown whenever any chrome is up (controller or banner) and no
        // other TopEnd overlay is active, to avoid double-stacking. The Formuler home has
        // no clock; at live-TV "is my show ending soon?" it's genuinely useful.
        val clockVisible =
            (controllerVisible || banner != null) &&
                numericInput.isEmpty() &&
                !statsOverlayVisible &&
                !tracksOverlayVisible
        AnimatedVisibility(
            visible = clockVisible,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            ClockOverlay()
        }

        // Numeric channel input OSD — centred top-right.
        AnimatedVisibility(
            visible = numericInput.isNotEmpty(),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            NumericOsd(numericInput)
        }

        // Stats overlay — top-right under the numeric OSD when visible.
        AnimatedVisibility(
            visible = statsOverlayVisible && statsSnapshot != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            statsSnapshot?.let { StatsOverlay(it, onDismiss = onDismissStats) }
        }

        // Keyhint: surface the "UP / MENU opens panel" shortcut while the Media3 controller
        // is visible. On live IPTV streams the controller often shows only play + timebar,
        // so without this hint the user has no way to discover the tracks panel.
        AnimatedVisibility(
            visible = controllerVisible && !tracksOverlayVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Text(
                text = stringResource(R.string.player_panel_hint),
                style = MaterialTheme.typography.labelSmall.copy(color = IptvPalette.TextSecondary),
                modifier = Modifier
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(IptvPalette.BackgroundDeep.copy(alpha = 0.75f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // Tracks panel — right-hand side, vertically centred.
        AnimatedVisibility(
            visible = tracksOverlayVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            TracksOverlay(
                snapshot = tracksSnapshot,
                aspectMode = aspectMode,
                subtitleDelayMs = subtitleDelayMs,
                onSelectAudio = onSelectAudio,
                onSelectSubtitle = onSelectSubtitle,
                onSelectAspect = onSelectAspect,
                onChangeSubtitleDelay = onChangeSubtitleDelay,
                onDismiss = onDismissTracks,
            )
        }

        // Error overlay eats the full screen and grabs focus so the user can recover.
        AnimatedVisibility(
            visible = errorState != null,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.fillMaxSize(),
        ) {
            errorState?.let {
                ErrorOverlay(
                    state = it,
                    onRetry = onRetryStream,
                    onSkip = onSkipError,
                    onExit = onExitOnError,
                )
            }
        }

        // "Volgende aflevering" countdown overlay — bottom-right when the current series
        // episode is about to end and the queue has another episode waiting. Does not
        // block focus (keeps it on the player controller) so the user can still scrub.
        AnimatedVisibility(
            visible = nextEpisode != null && errorState == null,
            enter = fadeIn(tween(240)) + slideInVertically(tween(260)) { it / 4 },
            exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 4 },
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            nextEpisode?.let {
                NextEpisodeOverlay(
                    info = it,
                    onPlayNow = onPlayNextEpisodeNow,
                    onCancel = onCancelNextEpisode,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NextEpisodeOverlay(
    info: NextEpisodeInfo,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    // Single focus target on the Play-now button. We request focus once when the overlay
    // mounts so pressing OK advances immediately without the user having to navigate.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier
            .padding(horizontal = 48.dp, vertical = 48.dp)
            .width(360.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(IptvPalette.BackgroundDeep.copy(alpha = 0.92f))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.player_next_episode_title).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = IptvPalette.Accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = info.nextName,
            style = MaterialTheme.typography.titleMedium.copy(
                color = IptvPalette.TextPrimary,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.player_next_episode_in, info.secondsRemaining),
            style = MaterialTheme.typography.labelMedium.copy(
                color = IptvPalette.TextSecondary,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPlayNow,
                modifier = Modifier.focusRequester(focus),
            ) {
                Text(
                    stringResource(R.string.player_next_episode_now),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Button(onClick = onCancel) {
                Text(
                    stringResource(R.string.player_next_episode_cancel),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun aspectResizeMode(mode: AspectMode): Int = when (mode) {
    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoBanner(banner: BannerInfo) {
    Row(
        modifier = Modifier
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(IptvPalette.BackgroundDeep.copy(alpha = 0.82f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (banner.channel.logoUrl != null) {
            AsyncImage(
                model = banner.channel.logoUrl,
                contentDescription = banner.channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 64.dp, height = 48.dp).padding(end = 12.dp),
            )
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                banner.channelNumber?.let { n ->
                    Text(
                        text = "%03d".format(n),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = IptvPalette.Accent,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                        ),
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
                Text(
                    text = banner.channel.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = IptvPalette.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(320.dp),
                )
            }
            // Prefer EPG "Nu:" / "Straks:" lines over the static group-title when we have
            // EPG data — they're the question the user actually cares about on a zap.
            val fallback = banner.channel.groupTitle
            val nowLine = banner.nowPlaying?.let { stringResource(R.string.player_banner_now, it) } ?: fallback
            if (!nowLine.isNullOrBlank()) {
                Text(
                    text = nowLine,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = IptvPalette.TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(320.dp),
                )
            }
            if (!banner.next.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.player_banner_next, banner.next),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = IptvPalette.TextTertiary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(320.dp),
                )
            }
        }
    }
}

@Composable
private fun ClockOverlay() {
    // Re-evaluate once a minute; wall-clock tick-every-second would burn recompositions
    // for a label no one is staring at. Producer re-emits on minute boundaries.
    val time = remember { androidx.compose.runtime.mutableStateOf(formatNowHhMm()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            time.value = formatNowHhMm()
            val now = java.util.Calendar.getInstance()
            val secondsIntoMinute = now.get(java.util.Calendar.SECOND)
            val millis = now.get(java.util.Calendar.MILLISECOND)
            val sleepMs = ((60 - secondsIntoMinute) * 1000L - millis).coerceAtLeast(1000L)
            kotlinx.coroutines.delay(sleepMs)
        }
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(IptvPalette.BackgroundDeep.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = time.value,
            style = MaterialTheme.typography.labelLarge.copy(
                color = IptvPalette.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            ),
        )
    }
}

private fun formatNowHhMm(): String {
    val cal = java.util.Calendar.getInstance()
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NumericOsd(input: String) {
    Column(
        modifier = Modifier
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(IptvPalette.BackgroundDeep.copy(alpha = 0.88f))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = stringResource(R.string.player_ch_input, input),
            style = MaterialTheme.typography.titleLarge.copy(
                color = IptvPalette.TextPrimary,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
            ),
        )
        Text(
            text = stringResource(R.string.player_ch_input_hint),
            style = MaterialTheme.typography.labelSmall.copy(color = IptvPalette.TextTertiary),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsOverlay(snapshot: StatsSnapshot, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(IptvPalette.BackgroundDeep.copy(alpha = 0.82f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.player_stats_title).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = IptvPalette.Accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.height(6.dp))
        StatsLine(stringResource(R.string.player_stats_resolution, snapshot.resolution))
        StatsLine(stringResource(R.string.player_stats_bitrate, snapshot.bitrate))
        StatsLine(stringResource(R.string.player_stats_codec, snapshot.codec))
        StatsLine(
            stringResource(
                R.string.player_stats_buffer,
                "${(snapshot.bufferMs / 1000).coerceAtLeast(0)}s",
            ),
        )
        StatsLine(stringResource(R.string.player_stats_dropped, snapshot.droppedFrames))
    }
}

@Composable
private fun StatsLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(color = IptvPalette.TextSecondary),
    )
}

@OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun TracksOverlay(
    snapshot: TracksSnapshot,
    aspectMode: AspectMode,
    subtitleDelayMs: Long,
    onSelectAudio: (trackIndex: Int, groupIndex: Int) -> Unit,
    onSelectSubtitle: (trackIndex: Int?, groupIndex: Int?) -> Unit,
    onSelectAspect: (AspectMode) -> Unit,
    onChangeSubtitleDelay: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstChipFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Pull focus into the panel as soon as it appears; without this DPAD events
        // never reach the subtitle / audio rows — they fall through to the Activity.
        runCatching { firstChipFocus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .padding(horizontal = 32.dp, vertical = 32.dp)
            .width(360.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(IptvPalette.BackgroundDeep.copy(alpha = 0.92f))
            .padding(16.dp),
    ) {
        // Aspect ratio quick-toggle — top block because it's the one control users hit most.
        Text(
            text = stringResource(R.string.player_aspect_title).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = IptvPalette.Accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AspectChip(
                R.string.player_aspect_fit,
                AspectMode.FIT,
                aspectMode,
                onSelectAspect,
                modifier = Modifier.focusRequester(firstChipFocus),
            )
            AspectChip(R.string.player_aspect_fill, AspectMode.FILL, aspectMode, onSelectAspect)
            AspectChip(R.string.player_aspect_zoom, AspectMode.ZOOM, aspectMode, onSelectAspect)
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.player_audio_title).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = IptvPalette.Accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.height(6.dp))
        val audioRows = remember(snapshot) {
            snapshot.audioGroups.flatMapIndexed { gi, group ->
                group.formats.mapIndexed { ti, fmt ->
                    TrackRowData(
                        label = fmt.language ?: fmt.label ?: "Audio",
                        sublabel = listOfNotNull(fmt.codecs, fmt.channelCount.takeIf { it > 0 }?.let { "${it}ch" })
                            .joinToString(" · "),
                        selected = group.selectedIndex == ti,
                        onClick = { onSelectAudio(ti, gi) },
                    )
                }
            }
        }
        if (audioRows.isEmpty()) {
            EmptyTrackHint(stringResource(R.string.player_audio_empty))
        } else {
            TvLazyColumn(
                modifier = Modifier.fillMaxWidth().height(140.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(audioRows, key = { it.label + it.sublabel }) { row -> TrackRow(row) }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.player_subtitle_title).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = IptvPalette.Accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.height(6.dp))
        val subRows = remember(snapshot) {
            val off = TrackRowData(
                label = "",
                sublabel = "",
                selected = !snapshot.subtitlesEnabled,
                onClick = { onSelectSubtitle(null, null) },
                offRow = true,
            )
            listOf(off) + snapshot.subtitleGroups.flatMapIndexed { gi, group ->
                group.formats.mapIndexed { ti, fmt ->
                    TrackRowData(
                        label = fmt.language ?: fmt.label ?: "Ondertitel",
                        sublabel = fmt.codecs.orEmpty(),
                        selected = group.selectedIndex == ti,
                        onClick = { onSelectSubtitle(ti, gi) },
                    )
                }
            }
        }
        TvLazyColumn(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(subRows, key = { (if (it.offRow) "__off__" else it.label + it.sublabel) }) { row ->
                TrackRow(row)
            }
        }
        if (snapshot.subtitleGroups.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            EmptyTrackHint(stringResource(R.string.player_subtitle_empty))
        }

        Spacer(Modifier.height(16.dp))
        SubtitleDelaySlider(
            valueMs = subtitleDelayMs,
            onChange = onChangeSubtitleDelay,
        )

        // Key hint strip: TV discoverability fix — users who opened this panel via the
        // green button or MENU key rarely know how to exit or navigate it. A compact,
        // non-focusable legend is cheaper than a tooltip and never steals D-pad focus.
        Spacer(Modifier.weight(1f, fill = false))
        Spacer(Modifier.height(10.dp))
        TracksKeyHint()
    }
}

@Composable
private fun TracksKeyHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(IptvPalette.SurfaceElevated.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.player_panel_hint_nav),
            style = MaterialTheme.typography.labelSmall.copy(
                color = IptvPalette.TextSecondary,
                letterSpacing = 1.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.player_panel_hint_back),
            style = MaterialTheme.typography.labelSmall.copy(
                color = IptvPalette.TextSecondary,
                letterSpacing = 1.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SubtitleDelaySlider(
    valueMs: Long,
    onChange: (Long) -> Unit,
    maxMs: Long = 10_000L,
    stepMs: Long = 100L,
) {
    var focused by remember { mutableStateOf(false) }
    val clamped = valueMs.coerceIn(0L, maxMs)
    val fraction = clamped.toFloat() / maxMs.toFloat()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { evt ->
                if (evt.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (evt.key) {
                    Key.DirectionLeft -> {
                        onChange((clamped - stepMs).coerceAtLeast(0L))
                        true
                    }
                    Key.DirectionRight -> {
                        onChange((clamped + stepMs).coerceAtMost(maxMs))
                        true
                    }
                    else -> false
                }
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) IptvPalette.Accent.copy(alpha = 0.22f)
                else IptvPalette.SurfaceElevated,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.player_subtitle_delay),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = formatDelayMs(clamped),
                style = MaterialTheme.typography.labelLarge.copy(
                    color = IptvPalette.Accent,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.2f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(IptvPalette.Accent),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.player_subtitle_delay_hint),
            style = MaterialTheme.typography.labelSmall.copy(color = IptvPalette.TextTertiary),
        )
    }
}

private fun formatDelayMs(ms: Long): String {
    if (ms <= 0L) return "0,0 s"
    val seconds = ms / 1000.0
    return "+%.1f s".format(seconds).replace('.', ',')
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyTrackHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(color = IptvPalette.TextTertiary),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private data class TrackRowData(
    val label: String,
    val sublabel: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val offRow: Boolean = false,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrackRow(data: TrackRowData) {
    val displayLabel = if (data.offRow) stringResource(R.string.player_subtitle_off) else data.label
    Surface(
        onClick = data.onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (data.selected) IptvPalette.Accent.copy(alpha = 0.25f) else IptvPalette.SurfaceElevated,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = IptvPalette.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (data.selected) "●" else "○",
                style = MaterialTheme.typography.labelLarge.copy(color = IptvPalette.Accent),
                modifier = Modifier.width(20.dp),
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = displayLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (data.sublabel.isNotBlank()) {
                    Text(
                        text = data.sublabel,
                        style = MaterialTheme.typography.labelSmall.copy(color = IptvPalette.TextTertiary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AspectChip(
    labelRes: Int,
    mode: AspectMode,
    current: AspectMode,
    onClick: (AspectMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = mode == current
    Surface(
        onClick = { onClick(mode) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) IptvPalette.Accent else IptvPalette.SurfaceElevated,
            contentColor = if (selected) Color.White else IptvPalette.TextSecondary,
            focusedContainerColor = IptvPalette.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorOverlay(
    state: ErrorState,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(
                        IptvPalette.AccentDeep.copy(alpha = 0.6f),
                        IptvPalette.BackgroundDeep.copy(alpha = 0.95f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = IptvPalette.TextPrimary,
                    fontWeight = FontWeight.Black,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.channelName,
                style = MaterialTheme.typography.titleMedium.copy(color = IptvPalette.TextSecondary),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall.copy(color = IptvPalette.TextTertiary),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) {
                    Text(
                        stringResource(R.string.player_error_retry),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
                if (state.canSkip) {
                    Button(onClick = onSkip) {
                        Text(
                            stringResource(R.string.player_error_next),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        )
                    }
                }
                Button(onClick = onExit) {
                    Text(
                        stringResource(R.string.player_error_exit),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
