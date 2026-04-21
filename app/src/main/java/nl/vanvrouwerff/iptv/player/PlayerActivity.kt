package nl.vanvrouwerff.iptv.player

import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.ChannelDao
import nl.vanvrouwerff.iptv.data.db.WatchProgressEntity
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
    private var previousIndex: Int = -1
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
    private var bannerNowPlaying by mutableStateOf<String?>(null)
    private var bannerNext by mutableStateOf<String?>(null)
    private var bannerChannelNumber by mutableStateOf<Int?>(null)
    private var numericInput by mutableStateOf("")
    private var errorOverlay by mutableStateOf<ErrorState?>(null)
    private var tracksOverlayVisible by mutableStateOf(false)
    private var controllerVisibleState by mutableStateOf(false)
    private var statsOverlayVisible by mutableStateOf(false)
    private var statsSnapshot by mutableStateOf<StatsSnapshot?>(null)
    private var aspectMode by mutableStateOf(AspectMode.FIT)
    private var tracksSnapshot by mutableStateOf(TracksSnapshot())
    private var subtitleDelayMs by mutableStateOf(0L)
    private var displayedCues by mutableStateOf<List<Cue>>(emptyList())

    private var bannerJob: Job? = null
    private var numericJob: Job? = null
    private var autoRetryJob: Job? = null

    /**
     * Transient-error retry counter. IPTV streams routinely hiccup (SSL handshake stalls,
     * ghost DNS failures, upstream blips); a silent retry after a beat recovers most of
     * them without the user ever seeing the error overlay. Reset on a successful play
     * and on channel change so genuinely-dead streams still pop the overlay on the next
     * hit rather than silently looping.
     */
    private var autoRetryCount = 0

    private val dao: ChannelDao get() = IptvApp.get().database.channelDao()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val startId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: run { finish(); return }
        val ids = intent.getStringArrayExtra(EXTRA_CHANNEL_IDS)?.toList().orEmpty()
        val resumeMs = intent.getLongExtra(EXTRA_RESUME_POSITION_MS, 0L).coerceAtLeast(0L)

        val adhocIds = intent.getStringArrayExtra(EXTRA_ADHOC_IDS)
        val adhocUrls = intent.getStringArrayExtra(EXTRA_ADHOC_URLS)
        val adhocNames = intent.getStringArrayExtra(EXTRA_ADHOC_NAMES)
        val adhocType = intent.getStringExtra(EXTRA_ADHOC_TYPE)

        setContent {
            IptvTheme {
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
                    controllerVisible = controllerVisibleState,
                    subtitleDelayMs = subtitleDelayMs,
                    displayedCues = displayedCues,
                    statsOverlayVisible = statsOverlayVisible,
                    statsSnapshot = statsSnapshot,
                    onPlayerViewReady = { view ->
                        playerViewRef = view
                        view.setControllerVisibilityListener(
                            androidx.media3.ui.PlayerView.ControllerVisibilityListener { vis ->
                                controllerVisibleState = vis == android.view.View.VISIBLE
                            },
                        )
                    },
                    onDismissTracks = { tracksOverlayVisible = false },
                    onDismissStats = { statsOverlayVisible = false },
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
                )
            }
        }

        lifecycleScope.launch {
            channels = when {
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
                else -> {
                    withContext(Dispatchers.Default) {
                        val all = dao.allChannels()
                            .asSequence()
                            .map { it.toDomain() }
                            .filter { it.streamUrl != null }
                            .toList()
                        val byId = all.associateBy { it.id }
                        ids.mapNotNull(byId::get).ifEmpty { all }
                    }
                }
            }
            if (channels.isEmpty()) { finish(); return@launch }
            currentIndex = channels.indexOfFirst { it.id == startId }.coerceAtLeast(0)
            initPlayer(resumeMs)
            // Show the banner for the opening channel so the user sees what's playing.
            showBanner(channels[currentIndex])
        }
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
        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
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
        // Nudge the system mixer out of a muted/zero state if whatever played before us
        // left MUSIC stream silenced — cheap safety on TV boxes with spotty drivers.
        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.let { am ->
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current == 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
                Log.i(TAG, "STREAM_MUSIC was 0, bumped to ${max / 2}")
            }
        }
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        // Stream is actually playing — any past transient errors are
                        // water under the bridge, reset so the next real error gets the
                        // full single-retry budget.
                        autoRetryCount = 0
                    }
                    Player.STATE_ENDED -> {
                        val channel = channels.getOrNull(currentIndex) ?: return
                        val isEpisode = channel.type == ContentType.SERIES
                        if (isEpisode && currentIndex < channels.size - 1) {
                            channelStep(+1)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val channel = channels.getOrNull(currentIndex)
                val code = error.errorCode
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
        playChannel(currentIndex, initialResumeMs)
        p.playWhenReady = true
        startProgressLoop()
        startStatsLoop()
        startCueLoop()
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
        if (index != currentIndex) previousIndex = currentIndex
        currentIndex = index
        errorOverlay = null
        // Channel change resets the auto-retry budget: a dead stream on the previous
        // channel must not consume the budget for this new stream.
        autoRetryCount = 0
        autoRetryJob?.cancel()
        // Drop any queued cues from the previous stream — their receivedAtMs references the
        // old media timeline and would mis-fire on the new one.
        synchronized(cueQueue) { cueQueue.clear() }
        displayedCues = emptyList()
        val p = player ?: return
        if (resumeMs > 0L) {
            p.setMediaItem(MediaItem.fromUri(url), resumeMs)
        } else {
            p.setMediaItem(MediaItem.fromUri(url))
        }
        p.prepare()
        lifecycleScope.launch {
            val profileId = IptvApp.get().activeProfileId.value
            IptvApp.get().settings.setLastWatched(profileId, channel.id)
        }
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
            droppedFrames = 0, // Media3 doesn't expose a simple accessor; placeholder.
        )
    }

    private fun saveCurrentProgress() {
        val channel = channels.getOrNull(currentIndex) ?: return
        if (channel.type == ContentType.TV) return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (pos <= 0 || dur <= 0) return
        val remaining = dur - pos
        val profileId = IptvApp.get().activeProfileId.value
        lifecycleScope.launch {
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
        val next = ((currentIndex + delta) % channels.size + channels.size) % channels.size
        playChannel(next)
        showBanner(channels[next])
    }

    private fun flipLastChannel() {
        val prev = previousIndex.takeIf { it in channels.indices } ?: return
        playChannel(prev)
        showBanner(channels[prev])
    }

    private fun jumpToChannelNumber(n: Int) {
        if (n < 1 || n > channels.size) return
        playChannel(n - 1)
        showBanner(channels[n - 1])
    }

    /** Show the info banner for `ch` and auto-hide after BANNER_MS. */
    private fun showBanner(ch: Channel) {
        bannerChannel = ch
        bannerChannelNumber = currentIndex + 1
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Numeric digits always enter the channel OSD — even when a panel is open; the user
        // intent is clearly "go to this channel", so we close panels implicitly.
        val digit = keyCode - KeyEvent.KEYCODE_0
        if (digit in 0..9) {
            tracksOverlayVisible = false
            statsOverlayVisible = false
            errorOverlay = null
            appendNumeric(digit)
            return true
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
        val pv = playerViewRef
        // When the built-in controller is already on screen, we hand D-pad events off to
        // it so seek/pause/subtitle buttons work the way a user expects. When it's hidden,
        // our own key semantics win (channel zap, etc.) and only OK wakes the controller up.
        val controllerVisible = pv?.isControllerFullyVisible == true
        val channelType = channels.getOrNull(currentIndex)?.type

        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                val p = player ?: return super.onKeyDown(keyCode, event)
                if (p.isPlaying) p.pause() else p.play()
                true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { channelStep(-1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { channelStep(+1); true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // UP only hijacks when focus is on the Media3 button row (play/pause etc.)
                // or nowhere — the button row has nothing usable above it, so opening our
                // tracks panel is a clear win. When focus is ON the timebar, UP must keep
                // the standard Media3 behaviour (move back to the button row).
                if (controllerVisible) {
                    val focusedId = pv?.findFocus()?.id
                    val onTimebar = focusedId == androidx.media3.ui.R.id.exo_progress
                    if (onTimebar && pv != null && event != null) {
                        pv.dispatchKeyEvent(event)
                        true
                    } else {
                        pv?.hideController()
                        tracksOverlayVisible = true
                        statsOverlayVisible = false
                        true
                    }
                } else if (channelType == ContentType.MOVIE) {
                    tracksOverlayVisible = true
                    statsOverlayVisible = false
                    true
                } else { channelStep(-1); true }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Forward DOWN to the PlayerView so the user can move from the button row
                // onto the timebar. On live TV without a controller, DOWN zaps the next
                // channel like before.
                if (controllerVisible || channelType == ContentType.MOVIE) {
                    if (pv != null && event != null) pv.dispatchKeyEvent(event) else false
                } else { channelStep(+1); true }
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                // Spoelen / scrubben: wake the controller and forward the event so its
                // timebar / rewind buttons handle it. Works for VOD; on live TV the user
                // gets the same visible timebar for a few seconds (buffer seek).
                if (pv != null && event != null) {
                    pv.showController()
                    pv.dispatchKeyEvent(event)
                    true
                } else super.onKeyDown(keyCode, event)
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
                pv?.hideController()
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when {
                    // OK while composing a channel number commits it immediately.
                    numericInput.isNotEmpty() -> { commitNumeric(); true }
                    pv != null -> {
                        // OK on empty playback screen = show the controller. Forwarding the
                        // event lets the controller focus one of its buttons so the next OK
                        // actually activates it (play/pause, subtitle, etc).
                        pv.showController()
                        event?.let { pv.dispatchKeyEvent(it) }
                        true
                    }
                    else -> super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStop() {
        super.onStop()
        saveCurrentProgress()
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
        playerViewRef?.player = null
        playerViewRef = null
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

        private const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val FINISH_THRESHOLD_MS = 30_000L
        /** How long the channel-info banner stays on screen after a zap. */
        private const val BANNER_MS = 3_200L
        /** Idle time before a partially-typed channel number auto-commits. */
        private const val NUMERIC_COMMIT_MS = 1_500L
        private const val MAX_SUBTITLE_DELAY_MS = 10_000L
        /** Silent-retry budget for transient IO errors before showing the overlay. */
        private const val MAX_AUTO_RETRY = 1
        private const val AUTO_RETRY_DELAY_MS = 1_500L
        private const val TAG = "PlayerActivity"
    }
}

enum class AspectMode { FIT, FILL, ZOOM }

data class BannerInfo(
    val channel: Channel,
    val nowPlaying: String?,
    val next: String?,
    val channelNumber: Int?,
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
