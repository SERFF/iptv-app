package nl.vanvrouwerff.iptv.player

import nl.vanvrouwerff.iptv.data.DisplayNames
import nl.vanvrouwerff.iptv.data.catchup.Catchup
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodecList
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.ChannelDao
import nl.vanvrouwerff.iptv.data.db.WatchProgressEntity
import nl.vanvrouwerff.iptv.data.db.WatchedEpisodeEntity
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.db.toDomain
import nl.vanvrouwerff.iptv.ui.theme.IptvTheme

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    /**
     * Live ref to the embedded PlayerView. Stored so onKeyDown can forward D-pad events to
     * the built-in controller (seek, pause, subtitle button) that the user can't otherwise
     * reach — the AndroidView inside a Compose tree never gets traversed by D-pad focus.
     */
    private var playerViewRef: PlayerView? = null

    private var channels: List<Channel> = emptyList()
    private var currentIndex: Int = 0
    private var previousChannel: Channel? = null
    private var frameRateMatching = true
    private var liveReturn: Pair<List<Channel>, Int>? = null
    private val frameRateProbe = FrameRateProbe()
    private var frameRateJob: kotlinx.coroutines.Job? = null
    private var liveProgramme by mutableStateOf<nl.vanvrouwerff.iptv.data.db.ProgrammeEntity?>(null)
    private var liveProgrammeJob: kotlinx.coroutines.Job? = null
    private var playingChannel: Channel? = null
    private var progressJob: Job? = null
    private var statsJob: Job? = null
    private var cueJob: Job? = null

    // Buffered subtitle cues so we can render them with a user-controlled delay. Each entry
    // remembers WHEN the cue was received so changing the delay slider takes effect without
    // flushing the queue — the release check is `receivedAtMs + delay <= currentPos`.
    private data class DelayedCue(val receivedAtMs: Long, val cues: List<Cue>)
    private val cueQueue = ArrayDeque<DelayedCue>()

    // Overlay state wired into the Compose layer. Mutated from onKeyDown / Player.Listener
    // so the UI updates without us having to push through a StateFlow for every tick.
    private var bannerChannel by mutableStateOf<Channel?>(null)
    /** Compose-observable ContentType of the currently playing item; drives Skip Intro. */
    private var currentChannelType by mutableStateOf<ContentType?>(null)
    private var bannerNowPlaying by mutableStateOf<String?>(null)
    private var bannerNext by mutableStateOf<String?>(null)
    private var bannerChannelNumber by mutableStateOf<Int?>(null)
    private var numericInput by mutableStateOf("")
    private var errorOverlay by mutableStateOf<ErrorState?>(null)
    private var tracksOverlayVisible by mutableStateOf(false)
    private var controlsVisible by mutableStateOf(false)
    private var controlsFocusToken by mutableStateOf(0)
    private var controlsHideJob: Job? = null
    private var statsOverlayVisible by mutableStateOf(false)
    private var statsSnapshot by mutableStateOf<StatsSnapshot?>(null)
    private var aspectMode by mutableStateOf(AspectMode.FIT)
    private var tracksSnapshot by mutableStateOf(TracksSnapshot())
    private var subtitleDelayMs by mutableStateOf(0L)
    private var displayedCues by mutableStateOf<List<Cue>>(emptyList())

    private var bannerJob: Job? = null
    private var numericJob: Job? = null
    private var autoRetryJob: Job? = null
    private var nextEpisodeJob: Job? = null

    private var nextEpisodeInfo by mutableStateOf<NextEpisodeInfo?>(null)

    /**
     * Set when the user dismisses the "Volgende aflevering"-overlay for the current
     * episode. Suppresses the overlay AND the auto-advance for the remainder of this
     * episode; resets on every channel change.
     */
    private var nextEpisodeCancelled = false

    /**
     * Transient-error retry counter. IPTV streams routinely hiccup (SSL handshake stalls,
     * ghost DNS failures, upstream blips); a silent retry after a beat recovers most of
     * them without the user ever seeing the error overlay. Reset on a successful play
     * and on channel change so genuinely-dead streams still pop the overlay on the next
     * hit rather than silently looping.
     */
    private var autoRetryCount = 0

    private var channelsLoaded = false
    private var pendingResumeMs = 0L
    private var loadJob: Job? = null
    private var droppedFrames = 0
    private var seriesMeta: SeriesMeta? = null

    /** Compose-observable id of the playing item; keys per-item overlay state (Skip Intro). */
    private var currentItemId by mutableStateOf<String?>(null)

    // Live TV: fixed channel numbering (favourites first, then the catalogue order) and the
    // in-player channel list. Both are only loaded when a live channel plays.
    private var numberedChannels: List<Channel> = emptyList()
    private var numberById: Map<String, Int> = emptyMap()
    private var channelGroups by mutableStateOf<List<ChannelGroup>>(emptyList())
    private var channelListVisible by mutableStateOf(false)
    private var channelGroupIndex by mutableStateOf(0)
    private var channelListNow by mutableStateOf<Map<String, NowInfo>>(emptyMap())
    private var liveIndexJob: Job? = null
    private var channelListNowJob: Job? = null
    private var openListWhenReady = false

    /** Series context for ad-hoc episode queues, so auto-advanced episodes land in "Verder kijken". */
    private class SeriesMeta(
        val seriesChannelId: String,
        val seriesName: String,
        val seasonNumber: Int,
        val episodeNumbers: IntArray?,
        val coverUrls: Array<String>?,
        val durationsSecs: LongArray?,
        val fallbackCover: String?,
    )

    private val dao: ChannelDao get() = IptvApp.get().database.channelDao()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (intent.getStringExtra(EXTRA_CHANNEL_ID) == null) { finish(); return }

        setContent {
            IptvTheme {
                androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    PlayerScreen(
                        playerProvider = { player },
                        aspectMode = aspectMode,
                        banner = bannerChannel?.let {
                            BannerInfo(
                                channel = it,
                                nowPlaying = bannerNowPlaying,
                                next = bannerNext,
                                channelNumber = bannerChannelNumber,
                            )
                        },
                        numericInput = numericInput,
                        errorState = errorOverlay,
                        tracksOverlayVisible = tracksOverlayVisible,
                        tracksSnapshot = tracksSnapshot,
                        controllerVisible = controlsVisible,
                        controls = controlsUi(),
                        onPlayPause = ::togglePlayPause,
                        onSeekBy = ::seekBy,
                        onOpenTracks = {
                            hideControls()
                            tracksOverlayVisible = true
                        },
                        onFromStart = {
                            player?.seekTo(0L)
                            bumpControlsTimer()
                        },
                        onNextEpisode = {
                            hideControls()
                            channelStep(+1)
                        },
                        onPreviousChannel = {
                            hideControls()
                            flipLastChannel()
                        },
                        onStartOver = {
                            hideControls()
                            startOver()
                        },
                        onControlsInteraction = ::bumpControlsTimer,
                        subtitleDelayMs = subtitleDelayMs,
                        displayedCues = displayedCues,
                        statsOverlayVisible = statsOverlayVisible,
                        statsSnapshot = statsSnapshot,
                        nextEpisode = nextEpisodeInfo,
                        isSeriesEpisode = currentChannelType == ContentType.SERIES,
                        currentItemId = currentItemId,
                        channelList = if (channelListVisible && channelGroups.isNotEmpty()) {
                            ChannelListUi(
                                groups = channelGroups,
                                groupIndex = channelGroupIndex.coerceIn(0, channelGroups.lastIndex),
                                currentChannelId = currentItemId,
                                nowByChannelId = channelListNow,
                                channelNumberOf = { numberById[it] },
                            )
                        } else null,
                        onSelectChannelGroup = ::selectChannelGroup,
                        onZapFromList = ::zapFromList,
                        onPlayerViewReady = { view -> playerViewRef = view },
                        onSelectAspect = { aspectMode = it },
                        onSelectAudio = ::applyAudioSelection,
                        onSelectSubtitle = ::applySubtitleSelection,
                        onChangeSubtitleDelay = ::setSubtitleDelay,
                        onRetryStream = ::retryCurrent,
                        onSkipError = {
                            errorOverlay = null
                            channelStep(+1)
                        },
                        onExitOnError = {
                            errorOverlay = null
                            finish()
                        },
                        onPlayNextEpisodeNow = {
                            nextEpisodeInfo = null
                            nextEpisodeCancelled = false
                            channelStep(+1)
                        },
                        onCancelNextEpisode = {
                            nextEpisodeInfo = null
                            nextEpisodeCancelled = true
                        },
                    )
                    nl.vanvrouwerff.iptv.ui.reminders.ReminderHost(onWatch = ::zapToChannelId)
                }
            }
        }

        loadFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        saveCurrentProgress()
        stopPlayback()
        loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: Intent) {
        val startId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: run { finish(); return }
        val ids = intent.getStringArrayExtra(EXTRA_CHANNEL_IDS)?.toList().orEmpty()
        val scopeType = intent.getStringExtra(EXTRA_SCOPE_TYPE)
        val resumeMs = intent.getLongExtra(EXTRA_RESUME_POSITION_MS, 0L).coerceAtLeast(0L)

        val adhocIds = intent.getStringArrayExtra(EXTRA_ADHOC_IDS)
        val adhocUrls = intent.getStringArrayExtra(EXTRA_ADHOC_URLS)
        val adhocNames = intent.getStringArrayExtra(EXTRA_ADHOC_NAMES)
        val adhocType = intent.getStringExtra(EXTRA_ADHOC_TYPE)

        seriesMeta = intent.getStringExtra(EXTRA_SERIES_CHANNEL_ID)?.let { seriesId ->
            SeriesMeta(
                seriesChannelId = seriesId,
                seriesName = intent.getStringExtra(EXTRA_SERIES_NAME).orEmpty(),
                seasonNumber = intent.getIntExtra(EXTRA_SERIES_SEASON, 0),
                episodeNumbers = intent.getIntArrayExtra(EXTRA_SERIES_EPISODE_NUMBERS),
                coverUrls = intent.getStringArrayExtra(EXTRA_SERIES_EPISODE_COVERS),
                durationsSecs = intent.getLongArrayExtra(EXTRA_SERIES_EPISODE_DURATIONS),
                fallbackCover = intent.getStringExtra(EXTRA_SERIES_COVER),
            )
        }

        channelsLoaded = false
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val loaded = when {
                adhocIds != null && adhocUrls != null && adhocIds.size == adhocUrls.size -> {
                    val type = adhocType
                        ?.let { runCatching { ContentType.valueOf(it) }.getOrNull() }
                        ?: ContentType.SERIES
                    adhocIds.indices.map { i ->
                        Channel(
                            id = adhocIds[i],
                            name = adhocNames?.getOrNull(i) ?: adhocIds[i],
                            logoUrl = null,
                            groupTitle = null,
                            streamUrl = adhocUrls[i],
                            epgChannelId = null,
                            type = type,
                        )
                    }
                }
                else -> withContext(Dispatchers.IO) { loadChannels(ids, scopeType) }
            }
            if (loaded.isEmpty()) { finish(); return@launch }
            channels = loaded
            currentIndex = loaded.indexOfFirst { it.id == startId }.coerceAtLeast(0)
            previousChannel = null
            playingChannel = null
            pendingResumeMs = resumeMs
            channelsLoaded = true
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startPlayback()
            if (loaded.getOrNull(currentIndex)?.type == ContentType.TV) loadLiveIndex()
        }
    }

    private suspend fun loadChannels(ids: List<String>, scopeType: String?): List<Channel> {
        val rows = when {
            ids.isNotEmpty() -> {
                val byId = ids.chunked(ID_QUERY_CHUNK)
                    .flatMap { dao.getChannelsByIds(it) }
                    .associateBy { it.id }
                ids.mapNotNull(byId::get)
            }
            scopeType != null -> dao.playableByType(scopeType)
            else -> emptyList()
        }
        return nl.vanvrouwerff.iptv.data.AdultContent.filterChannels(
            rows.map { it.toDomain() }.filter { it.streamUrl != null },
            IptvApp.get().kidsMode.value,
        ) { it.groupTitle }
    }

    override fun onStart() {
        super.onStart()
        if (channelsLoaded && player == null) startPlayback()
    }

    private fun startPlayback() {
        val resume = pendingResumeMs
        pendingResumeMs = 0L
        initPlayer(resume)
        channels.getOrNull(currentIndex)?.let(::showBanner)
    }

    private fun initPlayer(initialResumeMs: Long) {
        logAudioCapabilities()
        // Route audio through the media stream, enable decoder fallback (many IPTV
        // streams carry AC-3/E-AC-3 audio that the primary MediaCodec on the Formuler
        // refuses — the fallback decoder then picks it up instead of silently dropping
        // the audio track). `handleAudioFocus = true` makes ExoPlayer request focus so
        // the Formuler's AudioManager actually unmutes our output stream.
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
        // Extractors factory with ALL the TS flags that matter for IPTV:
        //  - ENABLE_HDMV_DTS_AUDIO_STREAMS: treats stream_type 0x82/0x85/0x86 as DTS (many
        //    Xtream VOD MKV-over-TS streams use these).
        //  - ALLOW_NON_IDR_KEYFRAMES + DETECT_ACCESS_UNITS: recover video/audio sync when
        //    the broadcaster doesn't mark keyframes cleanly.
        // Without these, AC-3 / DTS audio on MPEG-TS reports an empty sampleMimeType and
        // the audio renderer rejects it as unsupported → silent video, exactly what we saw
        // in logcat.
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
            )
        // OkHttp follows http→https / load-balancer redirects that DefaultHttpDataSource refuses.
        val dataSourceFactory = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(HttpClient.streaming))
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        val p = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .build()
        // Both `setExceedAudioConstraintsIfNecessary` and
        // `setExceedRendererCapabilitiesIfNecessary` live on DefaultTrackSelector.Parameters,
        // not on the plain TrackSelectionParameters builder returned by buildUpon(). The TS
        // flags + `.setEnableDecoderFallback(true)` above already handle the main IPTV
        // failure mode (Formuler decoder reporting "unsupported" for AC-3/DTS), so we leave
        // the selector at defaults rather than switching to DefaultTrackSelector.
        p.volume = 1f
        applyPlayerPreferences(p)
        p.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            frameRateProbe.add(presentationTimeUs)?.let { fps ->
                runOnUiThread {
                    Log.i(TAG, "Measured frame rate: $fps fps")
                    matchDisplayToFrameRate(fps)
                }
            }
        }
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                Log.i(TAG, "Video format ${format.width}x${format.height} @ ${format.frameRate} fps (${format.sampleMimeType})")
                if (format.frameRate > 0f) matchDisplayToFrameRate(format.frameRate) else frameRateProbe.reset()
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                this@PlayerActivity.droppedFrames += droppedFrames
            }
        })
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        measureFrameRate()
                        // Stream is actually playing — any past transient errors are
                        // water under the bridge, reset so the next real error gets the
                        // full single-retry budget.
                        autoRetryCount = 0
                    }
                    Player.STATE_ENDED -> {
                        val channel = channels.getOrNull(currentIndex) ?: return
                        when (channel.type) {
                            ContentType.SERIES -> {
                                // If the user dismissed the "Volgende aflevering"-overlay
                                // they're done with the queue — respect that instead of
                                // auto-advancing anyway.
                                if (nextEpisodeCancelled) return
                                if (currentIndex < channels.size - 1) {
                                    channelStep(+1)
                                }
                            }
                            ContentType.MOVIE -> if (liveReturn != null) returnToLive() else finish()
                            ContentType.TV -> {}
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val channel = channels.getOrNull(currentIndex)
                val code = error.errorCode
                if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player?.run {
                        seekToDefaultPosition()
                        prepare()
                    }
                    return
                }
                val isTransient = code in
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED..
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                if (isTransient && autoRetryCount < MAX_AUTO_RETRY) {
                    autoRetryCount++
                    Log.i(TAG, "transient IO error (${error.errorCodeName}); auto-retry $autoRetryCount")
                    autoRetryJob?.cancel()
                    autoRetryJob = lifecycleScope.launch {
                        delay(AUTO_RETRY_DELAY_MS)
                        // Channel might have changed during the wait (user hit CH+/-): skip
                        // the retry in that case, the new channel's own prepare() is running.
                        if (channels.getOrNull(currentIndex)?.id == channel?.id) retryCurrent()
                    }
                    return
                }
                // Fall through: pop a full-screen overlay rather than a Toast — on TV a
                // 2-line toast in the corner is easy to miss, and the user then sits
                // staring at a black screen wondering whether to touch anything.
                errorOverlay = ErrorState(
                    channelName = channel?.name.orEmpty(),
                    message = error.message ?: error.errorCodeName,
                    canSkip = channels.size > 1,
                )
            }

            override fun onCues(cueGroup: CueGroup) {
                if (subtitleDelayMs <= 0L) {
                    displayedCues = cueGroup.cues
                    return
                }
                val pos = player?.currentPosition ?: return
                synchronized(cueQueue) {
                    cueQueue.addLast(DelayedCue(pos, cueGroup.cues))
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksSnapshot = TracksSnapshot.from(tracks)
                // Diagnostic: dump the audio track summary so we can see in logcat which
                // codec / channel-count was picked (or skipped). Filter by tag `PlayerActivity`
                // — e.g. `adb logcat | grep PlayerActivity`.
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isEmpty()) {
                    Log.w(TAG, "onTracksChanged: stream has NO audio tracks")
                } else {
                    audio.forEachIndexed { gi, g ->
                        (0 until g.length).forEach { ti ->
                            val f = g.getTrackFormat(ti)
                            val selected = g.isTrackSelected(ti)
                            val supported = g.isTrackSupported(ti)
                            Log.i(
                                TAG,
                                "audio g=$gi t=$ti sampleMime=${f.sampleMimeType} " +
                                    "containerMime=${f.containerMimeType} codec=${f.codecs} " +
                                    "ch=${f.channelCount} sr=${f.sampleRate} " +
                                    "lang=${f.language} selected=$selected supported=$supported",
                            )
                        }
                    }
                }
            }
        })
        player = p
        playerViewRef?.player = p
        playChannel(currentIndex, initialResumeMs)
        p.playWhenReady = true
        startProgressLoop()
        startStatsLoop()
        startCueLoop()
        startNextEpisodeLoop()
    }

    /**
     * Polls the current playback position every 500 ms; when a SERIES episode is in its
     * last [NEXT_EPISODE_WINDOW_MS] and has a queued next item, surfaces the countdown
     * overlay. The overlay auto-hides once the player rolls onto the next episode
     * (channelStep fires from onPlaybackStateChanged), and stays suppressed for the rest
     * of the current episode if the user dismisses it.
     */
    private fun startNextEpisodeLoop() {
        nextEpisodeJob?.cancel()
        nextEpisodeJob = lifecycleScope.launch {
            while (isActive) {
                delay(500L)
                val p = player ?: continue
                val channel = channels.getOrNull(currentIndex) ?: continue
                if (channel.type != ContentType.SERIES || nextEpisodeCancelled) {
                    if (nextEpisodeInfo != null) nextEpisodeInfo = null
                    continue
                }
                val hasNext = currentIndex < channels.size - 1
                if (!hasNext) {
                    if (nextEpisodeInfo != null) nextEpisodeInfo = null
                    continue
                }
                val pos = p.currentPosition
                val dur = p.duration
                if (dur <= 0 || pos <= 0) {
                    if (nextEpisodeInfo != null) nextEpisodeInfo = null
                    continue
                }
                val remainingMs = dur - pos
                if (remainingMs in 0L..NEXT_EPISODE_WINDOW_MS) {
                    val secs = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(0)
                    val nextName = channels.getOrNull(currentIndex + 1)?.name.orEmpty()
                    val prev = nextEpisodeInfo
                    if (prev?.secondsRemaining != secs || prev.nextName != nextName) {
                        nextEpisodeInfo = NextEpisodeInfo(nextName, secs)
                    }
                } else if (nextEpisodeInfo != null) {
                    nextEpisodeInfo = null
                }
            }
        }
    }

    private fun startCueLoop() {
        cueJob?.cancel()
        cueJob = lifecycleScope.launch {
            while (isActive) {
                delay(50L)
                if (subtitleDelayMs <= 0L) continue
                val pos = player?.currentPosition ?: continue
                var latest: List<Cue>? = null
                synchronized(cueQueue) {
                    while (cueQueue.isNotEmpty() &&
                        cueQueue.first().receivedAtMs + subtitleDelayMs <= pos
                    ) {
                        latest = cueQueue.removeFirst().cues
                    }
                }
                latest?.let { displayedCues = it }
            }
        }
    }

    private fun setSubtitleDelay(ms: Long) {
        val clamped = ms.coerceIn(0L, MAX_SUBTITLE_DELAY_MS)
        subtitleDelayMs = clamped
        if (clamped == 0L) {
            synchronized(cueQueue) { cueQueue.clear() }
            displayedCues = player?.currentCues?.cues.orEmpty()
        }
    }

    private fun playChannel(index: Int, resumeMs: Long = 0L) {
        val channel = channels.getOrNull(index) ?: return
        val url = channel.streamUrl ?: return
        saveCurrentProgress()
        playingChannel?.let { if (it.id != channel.id && !Catchup.isCatchupId(it.id)) previousChannel = it }
        playingChannel = channel
        if (!Catchup.isCatchupId(channel.id)) liveReturn = null
        currentIndex = index
        currentChannelType = channel.type
        currentItemId = channel.id
        droppedFrames = 0
        errorOverlay = null
        // Channel change resets the auto-retry budget: a dead stream on the previous
        // channel must not consume the budget for this new stream.
        autoRetryCount = 0
        autoRetryJob?.cancel()
        // Reset the next-episode overlay state so a fresh episode starts the countdown
        // from scratch — without this, a user who dismissed the overlay on episode 3
        // would never see it on episode 4 (same activity instance, cancelled flag sticks).
        nextEpisodeInfo = null
        nextEpisodeCancelled = false
        // Drop any queued cues from the previous stream — their receivedAtMs references the
        // old media timeline and would mis-fire on the new one.
        synchronized(cueQueue) { cueQueue.clear() }
        displayedCues = emptyList()
        frameRateProbe.reset()
        frameRateJob?.cancel()
        loadLiveProgramme(channel)
        val p = player ?: return
        if (resumeMs > 0L) {
            p.setMediaItem(MediaItem.fromUri(url), resumeMs)
        } else {
            p.setMediaItem(MediaItem.fromUri(url))
        }
        p.prepare()
        val app = IptvApp.get()
        val profileId = app.activeProfileId.value
        if (Catchup.isCatchupId(channel.id)) return
        app.appScope.launch {
            app.settings.setLastWatched(profileId, channel.id)
            seriesMeta?.takeIf { channel.type == ContentType.SERIES }?.let { meta ->
                dao.rememberEpisode(
                    WatchedEpisodeEntity(
                        profileId = profileId,
                        episodeId = channel.id,
                        seriesChannelId = meta.seriesChannelId,
                        seriesName = meta.seriesName,
                        seasonNumber = meta.seasonNumber,
                        episodeNumber = meta.episodeNumbers?.getOrNull(index) ?: 0,
                        episodeTitle = channel.name,
                        streamUrl = url,
                        coverUrl = meta.coverUrls?.getOrNull(index)?.takeIf { it.isNotBlank() }
                            ?: meta.fallbackCover,
                        durationSecs = meta.durationsSecs?.getOrNull(index) ?: 0L,
                        firstWatchedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun loadLiveProgramme(channel: Channel) {
        liveProgrammeJob?.cancel()
        liveProgramme = null
        val key = channel.epgChannelId
        if (channel.type != ContentType.TV || channel.archiveDays <= 0 || key.isNullOrBlank()) return
        liveProgrammeJob = lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { dao.getNowPlayingFor(key, System.currentTimeMillis()) }
            if (playingChannel?.id == channel.id) liveProgramme = p
        }
    }

    /** Replays the programme that is on now from its start, via the channel's archive. */
    private fun startOver() {
        val live = channels.getOrNull(currentIndex)?.takeIf { it.type == ContentType.TV } ?: return
        val programme = liveProgramme ?: return
        lifecycleScope.launch {
            val item = Catchup.item(live, programme.title, programme.startMs, programme.stopMs) ?: return@launch
            if (playingChannel?.id != live.id) return@launch
            liveReturn = channels to currentIndex
            channels = listOf(item)
            playChannel(0)
        }
    }

    private fun returnToLive() {
        val (list, index) = liveReturn ?: return
        liveReturn = null
        channels = list
        playChannel(index)
        channels.getOrNull(index)?.let(::showBanner)
    }

    private fun retryCurrent() {
        errorOverlay = null
        val p = player ?: return
        p.prepare()
        p.playWhenReady = true
    }

    /**
     * Dump what the current audio output can accept (PCM? AC-3 passthrough? E-AC-3? DTS?)
     * plus every audio decoder MediaCodec exposes, so we can tell the difference between
     * "codec decoder is missing" and "HDMI passthrough is disabled" when a track reports
     * `supported=false`.
     */
    private fun logAudioCapabilities() {
        runCatching {
            val caps = AudioCapabilities.getCapabilities(this)
            val encodings = listOf(
                AudioFormat.ENCODING_PCM_16BIT to "PCM_16",
                AudioFormat.ENCODING_AC3 to "AC3",
                AudioFormat.ENCODING_E_AC3 to "E_AC3",
                AudioFormat.ENCODING_E_AC3_JOC to "E_AC3_JOC",
                AudioFormat.ENCODING_DTS to "DTS",
                AudioFormat.ENCODING_DTS_HD to "DTS_HD",
                AudioFormat.ENCODING_DOLBY_TRUEHD to "TRUEHD",
            )
            val supported = encodings.filter { (enc, _) -> caps.supportsEncoding(enc) }
                .joinToString(", ") { it.second }
            Log.i(
                TAG,
                "AudioCapabilities: maxChannelCount=${caps.maxChannelCount} encodings=[$supported]",
            )
        }.onFailure { Log.w(TAG, "AudioCapabilities query failed", it) }

        runCatching {
            val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val audioDecoders = mcl.codecInfos
                .asSequence()
                .filter { !it.isEncoder }
                .flatMap { info ->
                    info.supportedTypes.asSequence().filter { it.startsWith("audio/") }
                        .map { "${info.name}→$it" }
                }
                .toList()
            Log.i(TAG, "audio decoders (${audioDecoders.size}):")
            audioDecoders.forEach { Log.i(TAG, "  $it") }
        }.onFailure { Log.w(TAG, "MediaCodecList query failed", it) }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                saveCurrentProgress()
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                if (statsOverlayVisible) statsSnapshot = snapshotStats()
            }
        }
    }

    private fun snapshotStats(): StatsSnapshot? {
        val p = player ?: return null
        val videoFormat = p.videoFormat
        val audioFormat = p.audioFormat
        val resolution = videoFormat?.let { "${it.width}×${it.height}" } ?: "—"
        val bitrate = videoFormat?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "—"
        val codec = listOfNotNull(videoFormat?.codecs, audioFormat?.codecs)
            .joinToString(", ").ifBlank { "—" }
        val buffered = p.totalBufferedDuration
        return StatsSnapshot(
            resolution = resolution,
            bitrate = bitrate,
            codec = codec,
            bufferMs = buffered,
            droppedFrames = droppedFrames,
        )
    }

    private fun saveCurrentProgress() {
        val channel = channels.getOrNull(currentIndex) ?: return
        if (channel.type == ContentType.TV || Catchup.isCatchupId(channel.id)) return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (pos <= 0 || dur <= 0) return
        val remaining = dur - pos
        val profileId = IptvApp.get().activeProfileId.value
        // App scope: this also runs from onStop right before the activity (and its
        // lifecycleScope) is destroyed, and the final position must not be dropped.
        IptvApp.get().appScope.launch {
            val savePos = if (remaining < FINISH_THRESHOLD_MS) dur else pos
            dao.saveProgress(
                WatchProgressEntity(
                    profileId = profileId,
                    channelId = channel.id,
                    positionMs = savePos,
                    durationMs = dur,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun channelStep(delta: Int) {
        if (channels.isEmpty()) return
        val wraps = channels.getOrNull(currentIndex)?.type == ContentType.TV
        val next = if (wraps) {
            ((currentIndex + delta) % channels.size + channels.size) % channels.size
        } else {
            (currentIndex + delta).takeIf { it in channels.indices } ?: return
        }
        playChannel(next)
        showBanner(channels[next])
    }

    private fun zapToChannelId(id: String) {
        val inList = channels.indexOfFirst { it.id == id }
        if (inList >= 0) {
            playChannel(inList)
            showBanner(channels[inList])
            return
        }
        val numbered = numberedChannels.indexOfFirst { it.id == id }
        if (numbered >= 0) {
            channels = numberedChannels
            playChannel(numbered)
            showBanner(channels[numbered])
            return
        }
        lifecycleScope.launch {
            val ch = withContext(Dispatchers.IO) { dao.getChannelsByIds(listOf(id)) }.firstOrNull()?.toDomain() ?: return@launch
            channels = listOf(ch)
            playChannel(0)
            showBanner(ch)
            loadLiveIndex()
        }
    }

    private fun flipLastChannel() {
        val prev = previousChannel ?: return
        var index = channels.indexOfFirst { it.id == prev.id }
        if (index < 0) {
            val numbered = numberedChannels.indexOfFirst { it.id == prev.id }
            if (numbered < 0) return
            channels = numberedChannels
            index = numbered
        }
        playChannel(index)
        showBanner(channels[index])
    }

    private fun jumpToChannelNumber(n: Int) {
        // Live TV has a fixed numbering independent of the rail the user started from:
        // switch the zap list to it so ▲▼ continue from the chosen number.
        if (numberedChannels.isNotEmpty() && channels.getOrNull(currentIndex)?.type == ContentType.TV) {
            if (n < 1 || n > numberedChannels.size) return
            val target = numberedChannels[n - 1]
            if (channels !== numberedChannels) {
                val currentId = channels.getOrNull(currentIndex)?.id
                channels = numberedChannels
                currentIndex = numberedChannels.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            }
            playChannel(n - 1)
            showBanner(target)
            return
        }
        if (n < 1 || n > channels.size) return
        playChannel(n - 1)
        showBanner(channels[n - 1])
    }

    /**
     * Builds the fixed live numbering and the channel-list groups: favourites (in the
     * user's order) first, then every category in catalogue order.
     */
    private fun loadLiveIndex() {
        if (liveIndexJob?.isActive == true || numberedChannels.isNotEmpty()) return
        liveIndexJob = lifecycleScope.launch {
            val index = withContext(Dispatchers.IO) {
                nl.vanvrouwerff.iptv.data.live.LiveChannelIndex.load(
                    dao = dao,
                    profileId = IptvApp.get().activeProfileId.value,
                    favoritesLabel = getString(R.string.channel_list_favorites),
                    uncategorizedLabel = getString(R.string.channel_list_uncategorized),
                    hideAdult = IptvApp.get().kidsMode.value,
                )
            }
            val built = Triple(
                index.numbered,
                index.numberById,
                index.groups.map { ChannelGroup(it.title, it.channels) },
            )
            numberedChannels = built.first
            numberById = built.second
            channelGroups = built.third
            channels.getOrNull(currentIndex)?.let { current ->
                if (bannerChannel?.id == current.id) bannerChannelNumber = numberById[current.id]
            }
            if (openListWhenReady) {
                openListWhenReady = false
                openChannelList()
            }
        }
    }

    private fun openChannelList() {
        if (channelGroups.isEmpty()) {
            openListWhenReady = true
            loadLiveIndex()
            return
        }
        val current = channels.getOrNull(currentIndex)
        val byTitle = channelGroups.indexOfFirst { g -> g.title == current?.groupTitle }
        val containing = channelGroups.indexOfFirst { g -> g.channels.any { it.id == current?.id } }
        channelGroupIndex = when {
            channels.size == channelGroups.firstOrNull()?.channels?.size &&
                channelGroups.firstOrNull()?.channels?.any { it.id == current?.id } == true -> 0
            byTitle >= 0 -> byTitle
            containing >= 0 -> containing
            else -> 0
        }
        controlsVisible = false
        tracksOverlayVisible = false
        statsOverlayVisible = false
        bannerChannel = null
        channelListVisible = true
        refreshChannelListNow()
    }

    private fun selectChannelGroup(index: Int) {
        channelGroupIndex = index
        refreshChannelListNow()
    }

    private fun refreshChannelListNow() {
        val group = channelGroups.getOrNull(channelGroupIndex) ?: return
        channelListNowJob?.cancel()
        channelListNowJob = lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val keys = group.channels.mapNotNull { it.epgChannelId }.distinct()
            val programmes = withContext(Dispatchers.IO) {
                keys.chunked(500).flatMap { dao.nowPlayingForKeys(it, now) }
            }.associateBy { it.channelKey }
            channelListNow = group.channels.mapNotNull { ch ->
                val p = ch.epgChannelId?.let(programmes::get) ?: return@mapNotNull null
                val span = (p.stopMs - p.startMs).coerceAtLeast(1L)
                ch.id to NowInfo(p.title, (now - p.startMs).toFloat() / span)
            }.toMap()
        }
    }

    private fun zapFromList(group: ChannelGroup, channel: Channel) {
        val index = group.channels.indexOfFirst { it.id == channel.id }
        if (index < 0) return
        if (channels != group.channels) {
            channels = group.channels
            currentIndex = -1
        }
        playChannel(index)
    }

    /** Show the info banner for `ch` and auto-hide after BANNER_MS. */
    private fun showBanner(ch: Channel) {
        bannerChannel = ch
        bannerChannelNumber = numberById[ch.id] ?: (currentIndex + 1)
        // Start empty so the banner pops immediately; the EPG lookup populates async and
        // the UI updates in-place. A single Room hit per zap is negligible next to the
        // player prepare() cost and gives the user a real "Nu:" line instead of stale.
        bannerNowPlaying = null
        bannerNext = null
        bannerJob?.cancel()
        val epgKey = ch.epgChannelId
        bannerJob = lifecycleScope.launch {
            if (!epgKey.isNullOrBlank()) {
                val now = System.currentTimeMillis()
                val nowProgramme = withContext(Dispatchers.IO) { dao.getNowPlayingFor(epgKey, now) }
                val nextProgramme = withContext(Dispatchers.IO) { dao.getNextProgrammeFor(epgKey, now) }
                // Only push the update if the banner is still showing the same channel —
                // otherwise a rapid CH+/CH- could stamp stale data on the new channel.
                if (bannerChannel?.id == ch.id) {
                    bannerNowPlaying = nowProgramme?.title
                    bannerNext = nextProgramme?.title
                }
            }
            delay(BANNER_MS)
            bannerChannel = null
            bannerNowPlaying = null
            bannerNext = null
        }
    }

    private fun appendNumeric(digit: Int) {
        val current = numericInput
        // Don't let the user compose absurdly long numbers — 4 digits is plenty for an
        // IPTV playlist (even 5000 zenders fits in 4).
        if (current.length >= 4) return
        numericInput = current + digit
        numericJob?.cancel()
        numericJob = lifecycleScope.launch {
            delay(NUMERIC_COMMIT_MS)
            commitNumeric()
        }
    }

    private fun commitNumeric() {
        val n = numericInput.toIntOrNull()
        numericInput = ""
        numericJob?.cancel()
        if (n != null) jumpToChannelNumber(n)
    }

    private fun applyAudioSelection(trackIndex: Int, groupIndex: Int) {
        val p = player ?: return
        val group = tracksSnapshot.audioGroups.getOrNull(groupIndex) ?: return
        val override = TrackSelectionOverride(group.mediaGroup, trackIndex)
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(override)
            .build()
    }

    private fun applySubtitleSelection(trackIndex: Int?, groupIndex: Int?) {
        val p = player ?: return
        val builder: TrackSelectionParameters.Builder = p.trackSelectionParameters.buildUpon()
        if (trackIndex == null || groupIndex == null) {
            // "Uit" — wipe any override on subtitles.
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = tracksSnapshot.subtitleGroups.getOrNull(groupIndex) ?: return
            builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaGroup, trackIndex))
        }
        p.trackSelectionParameters = builder.build()
    }

    /** Default aspect ratio and preferred audio/subtitle languages from Instellingen. */
    private fun applyPlayerPreferences(p: ExoPlayer) {
        val settings = IptvApp.get().settings
        lifecycleScope.launch {
            val aspect = settings.playerAspect.first()
            val audio = settings.preferredAudioLanguage.first()
            val subtitles = settings.preferredSubtitleLanguage.first()
            val tunneling = settings.hardwareAvSync.first()
            frameRateMatching = settings.frameRateMatching.first()
            aspectMode = runCatching { AspectMode.valueOf(aspect) }.getOrDefault(AspectMode.FIT)
            val builder = p.trackSelectionParameters.buildUpon()
            if (audio.isNotBlank()) builder.setPreferredAudioLanguage(audio)
            when (subtitles) {
                "" -> Unit
                "off" -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else -> builder
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(subtitles)
            }
            (builder as? DefaultTrackSelector.Parameters.Builder)?.setTunnelingEnabled(tunneling)
            if (player === p) p.trackSelectionParameters = builder.build()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Numeric digits always enter the channel OSD — even when a panel is open; the user
        // intent is clearly "go to this channel", so we close panels implicitly.
        if (IptvApp.get().reminders.due.value != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK,
                -> return super.onKeyDown(keyCode, event)
            }
        }
        val digit = keyCode - KeyEvent.KEYCODE_0
        if (digit in 0..9) {
            channelListVisible = false
            tracksOverlayVisible = false
            statsOverlayVisible = false
            errorOverlay = null
            appendNumeric(digit)
            return true
        }
        // Error / next-episode overlays own the D-pad: their buttons have focus, and any key
        // Compose didn't consume must not zap to another channel underneath them.
        if (errorOverlay != null || nextEpisodeInfo != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                -> return true
            }
        }
        if (channelListVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    channelListVisible = false
                    playerViewRef?.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                -> return super.onKeyDown(keyCode, event)
            }
        }
        // While a Compose overlay panel is up, let D-pad / OK fall through to the focused
        // Surface inside it instead of zapping channels or waking the PlayerView controller.
        if (tracksOverlayVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    tracksOverlayVisible = false
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                -> return super.onKeyDown(keyCode, event)
            }
        }
        if (statsOverlayVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    statsOverlayVisible = false
                    return true
                }
            }
        }
        val channelType = channels.getOrNull(currentIndex)?.type
        val isLive = channelType == ContentType.TV

        // Controls visible: the focused Compose button/timebar owns D-pad and OK.
        if (controlsVisible) {
            bumpControlsTimer()
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    hideControls()
                    return true
                }
                KeyEvent.KEYCODE_PROG_YELLOW -> {
                    hideControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                -> return super.onKeyDown(keyCode, event)
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (liveReturn != null) {
                    returnToLive()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                if (!isLive) showControls()
                true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { channelStep(+1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { channelStep(-1); true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isLive) channelStep(-1) else {
                    tracksOverlayVisible = true
                    statsOverlayVisible = false
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isLive) channelStep(+1) else showControls()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                // Spoelen: seek straight away and show the timebar so the jump is visible.
                if (!isLive) {
                    val back = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                    val step = seekStepMs(event?.repeatCount ?: 0)
                    seekBy(if (back) -step else step)
                }
                showControls()
                true
            }
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_PROG_RED -> {
                flipLastChannel(); true
            }
            KeyEvent.KEYCODE_INFO -> {
                channels.getOrNull(currentIndex)?.let { showBanner(it) }
                true
            }
            KeyEvent.KEYCODE_PROG_GREEN,
            KeyEvent.KEYCODE_CAPTIONS,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            -> {
                tracksOverlayVisible = !tracksOverlayVisible
                statsOverlayVisible = false
                true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                statsOverlayVisible = !statsOverlayVisible
                if (statsOverlayVisible) statsSnapshot = snapshotStats()
                tracksOverlayVisible = false
                true
            }
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                // Live TV: OK opens the channel list, so the controls get their own key.
                showControls()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when {
                    // OK while composing a channel number commits it immediately.
                    numericInput.isNotEmpty() -> commitNumeric()
                    isLive -> openChannelList()
                    else -> showControls()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showControls() {
        channelListVisible = false
        controlsFocusToken++
        controlsVisible = true
        bumpControlsTimer()
    }

    private fun hideControls() {
        controlsVisible = false
        controlsHideJob?.cancel()
        playerViewRef?.requestFocus()
    }

    /** Auto-hide after a few idle seconds, but never while paused. */
    private fun bumpControlsTimer() {
        controlsHideJob?.cancel()
        controlsHideJob = lifecycleScope.launch {
            delay(CONTROLS_TIMEOUT_MS)
            while (player?.isPlaying == false) delay(CONTROLS_TIMEOUT_MS)
            hideControls()
        }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, duration))
    }

    private fun controlsUi(): ControlsUi? {
        if (!controlsVisible) return null
        // Read the observable id so the controls recompose with the new title after a zap.
        val currentId = currentItemId
        val current = channels.getOrNull(currentIndex)?.takeIf { it.id == currentId } ?: return null
        val meta = seriesMeta
        val isSeries = current.type == ContentType.SERIES
        val episodeNumber = meta?.episodeNumbers?.getOrNull(currentIndex)
        val title = if (isSeries && meta != null && meta.seriesName.isNotBlank()) meta.seriesName else current.name
        val subtitle = when {
            isSeries && meta != null && episodeNumber != null ->
                "S${meta.seasonNumber}:A$episodeNumber \u00B7 ${current.name}"
            isSeries -> current.name
            else -> current.groupTitle?.let(DisplayNames::clean)
        }
        return ControlsUi(
            title = title,
            subtitle = subtitle,
            isLive = current.type == ContentType.TV,
            hasNextEpisode = isSeries && currentIndex < channels.lastIndex,
            hasPreviousChannel = current.type == ContentType.TV && previousChannel != null,
            canStartOver = current.type == ContentType.TV && liveProgramme != null,
            focusToken = controlsFocusToken,
        )
    }

    /** Fallback when neither the container nor the frame callbacks give a frame rate (tunneled TS). */
    private fun measureFrameRate() {
        if (!frameRateMatching || frameRateProbe.hasReported()) return
        frameRateJob?.cancel()
        val p = player ?: return
        frameRateJob = lifecycleScope.launch {
            delay(FRAME_RATE_SETTLE_MS)
            val c0 = p.videoDecoderCounters?.renderedOutputBufferCount ?: return@launch
            val t0 = android.os.SystemClock.elapsedRealtime()
            delay(FRAME_RATE_WINDOW_MS)
            if (player !== p || !p.isPlaying || frameRateProbe.hasReported()) return@launch
            val c1 = p.videoDecoderCounters?.renderedOutputBufferCount ?: return@launch
            val seconds = (android.os.SystemClock.elapsedRealtime() - t0) / 1000f
            val measured = (c1 - c0) / seconds
            val fps = FrameRateProbe.snap(measured) ?: return@launch
            frameRateProbe.markReported()
            Log.i(TAG, "Measured frame rate: $measured → $fps fps")
            matchDisplayToFrameRate(fps)
        }
    }

    private fun matchDisplayToFrameRate(fps: Float) {
        if (!frameRateMatching) return
        val display = window.decorView.display ?: return
        val current = display.mode
        fun toMode(m: android.view.Display.Mode) =
            FrameRateMatcher.Mode(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate)
        val target = FrameRateMatcher.pick(
            fps = fps,
            current = toMode(current),
            available = display.supportedModes.map(::toMode),
        ) ?: return
        if (window.attributes.preferredDisplayModeId == target.id) return
        Log.i(TAG, "Frame rate $fps fps: switching display to ${target.width}x${target.height}@${target.refreshRate}")
        window.attributes = window.attributes.also { it.preferredDisplayModeId = target.id }
    }

    override fun onStop() {
        super.onStop()
        if (window.attributes.preferredDisplayModeId != 0) {
            window.attributes = window.attributes.also { it.preferredDisplayModeId = 0 }
        }
        val p = player
        val current = channels.getOrNull(currentIndex)
        if (p != null && current != null && current.type != ContentType.TV) {
            pendingResumeMs = p.currentPosition.coerceAtLeast(0L)
        }
        saveCurrentProgress()
        stopPlayback()
    }

    private fun stopPlayback() {
        progressJob?.cancel()
        progressJob = null
        statsJob?.cancel()
        statsJob = null
        cueJob?.cancel()
        cueJob = null
        synchronized(cueQueue) { cueQueue.clear() }
        displayedCues = emptyList()
        bannerJob?.cancel()
        bannerJob = null
        numericJob?.cancel()
        numericJob = null
        autoRetryJob?.cancel()
        autoRetryJob = null
        nextEpisodeJob?.cancel()
        nextEpisodeJob = null
        nextEpisodeInfo = null
        playerViewRef?.player = null
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CHANNEL_IDS = "channel_ids"
        const val EXTRA_RESUME_POSITION_MS = "resume_position_ms"

        /** Parallel arrays describing an ad-hoc playlist (e.g. series episodes) not in Room. */
        const val EXTRA_ADHOC_IDS = "adhoc_ids"
        const val EXTRA_ADHOC_URLS = "adhoc_urls"
        const val EXTRA_ADHOC_NAMES = "adhoc_names"
        const val EXTRA_ADHOC_TYPE = "adhoc_type"

        /** Content type whose full playable list is the zap list; used instead of huge id arrays. */
        const val EXTRA_SCOPE_TYPE = "scope_type"

        const val EXTRA_SERIES_CHANNEL_ID = "series_channel_id"
        const val EXTRA_SERIES_NAME = "series_name"
        const val EXTRA_SERIES_COVER = "series_cover"
        const val EXTRA_SERIES_SEASON = "series_season"
        const val EXTRA_SERIES_EPISODE_NUMBERS = "series_episode_numbers"
        const val EXTRA_SERIES_EPISODE_COVERS = "series_episode_covers"
        const val EXTRA_SERIES_EPISODE_DURATIONS = "series_episode_durations"

        /** Above this many ids the caller passes [EXTRA_SCOPE_TYPE] instead (binder size limit). */
        const val MAX_INTENT_IDS = 1_000
        private const val ID_QUERY_CHUNK = 900

        private const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val FINISH_THRESHOLD_MS = 30_000L
        /** How long the channel-info banner stays on screen after a zap. */
        private const val CONTROLS_TIMEOUT_MS = 5_000L
        private const val BANNER_MS = 3_200L
        /** Idle time before a partially-typed channel number auto-commits. */
        private const val NUMERIC_COMMIT_MS = 1_500L
        private const val MAX_SUBTITLE_DELAY_MS = 10_000L
        /** Silent-retry budget for transient IO errors before showing the overlay. */
        private const val MAX_AUTO_RETRY = 1
        private const val AUTO_RETRY_DELAY_MS = 1_500L
        /** Remaining-playback threshold that triggers the "Volgende aflevering"-overlay. */
        const val NEXT_EPISODE_WINDOW_MS: Long = 15_000L
        private const val TAG = "PlayerActivity"
        private const val FRAME_RATE_SETTLE_MS = 2_500L
        private const val FRAME_RATE_WINDOW_MS = 2_000L
    }
}

enum class AspectMode { FIT, FILL, ZOOM }

data class BannerInfo(
    val channel: Channel,
    val nowPlaying: String?,
    val next: String?,
    val channelNumber: Int?,
)

/**
 * Live state for the "Volgende aflevering"-overlay. Populated during the last
 * [NEXT_EPISODE_WINDOW_MS] of a series episode when the queue has another episode
 * waiting; the overlay counts down and the player auto-advances on zero.
 */
data class NextEpisodeInfo(
    val nextName: String,
    val secondsRemaining: Int,
)

data class ErrorState(
    val channelName: String,
    val message: String,
    val canSkip: Boolean,
)

data class StatsSnapshot(
    val resolution: String,
    val bitrate: String,
    val codec: String,
    val bufferMs: Long,
    val droppedFrames: Int,
)

/**
 * Flattened view of the current Tracks object so the Compose layer doesn't have to reach
 * into Media3 types. Only audio + subtitle rows are listed — the video renderer picks
 * adaptively and we don't want to expose that knob.
 */
@OptIn(UnstableApi::class)
data class TracksSnapshot(
    val audioGroups: List<TrackGroupSummary> = emptyList(),
    val subtitleGroups: List<TrackGroupSummary> = emptyList(),
    val subtitlesEnabled: Boolean = false,
) {
    companion object {
        fun from(tracks: Tracks): TracksSnapshot {
            val audio = mutableListOf<TrackGroupSummary>()
            val subs = mutableListOf<TrackGroupSummary>()
            var subsEnabled = false
            tracks.groups.forEach { group ->
                val formats = (0 until group.length).map { i -> group.getTrackFormat(i) }
                val summary = TrackGroupSummary(
                    mediaGroup = group.mediaTrackGroup,
                    formats = formats,
                    selectedIndex = (0 until group.length).firstOrNull { group.isTrackSelected(it) },
                )
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio.add(summary)
                    C.TRACK_TYPE_TEXT -> {
                        subs.add(summary)
                        if (summary.selectedIndex != null) subsEnabled = true
                    }
                    else -> Unit
                }
            }
            return TracksSnapshot(
                audioGroups = audio,
                subtitleGroups = subs,
                subtitlesEnabled = subsEnabled,
            )
        }
    }
}

@OptIn(UnstableApi::class)
data class TrackGroupSummary(
    val mediaGroup: androidx.media3.common.TrackGroup,
    val formats: List<Format>,
    val selectedIndex: Int?,
)
