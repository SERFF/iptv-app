package nl.vanvrouwerff.iptv.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.toDomain
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.settings.SourceConfig
import nl.vanvrouwerff.iptv.data.tmdb.MovieMatcher
import nl.vanvrouwerff.iptv.data.tmdb.TmdbCatalogueMatcher
import nl.vanvrouwerff.iptv.data.tmdb.TmdbMovieDetailsRepository
import nl.vanvrouwerff.iptv.data.xtream.XtreamApi

data class MovieDetailState(
    val loading: Boolean = true,
    val channel: Channel? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isFavorite: Boolean = false,
    // Rich metadata — all optional; filled in once get_vod_info returns.
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseYear: String? = null,
    val rating: String? = null,
    val durationLabel: String? = null,
    val country: String? = null,
    /**
     * Trailer URL sourced from either Xtream VOD info (`youtube_trailer`) or TMDB's
     * videos endpoint. Expressed as a fully-qualified YouTube URL so the UI can fire
     * an ACTION_VIEW intent without worrying about the source.
     */
    val trailerUrl: String? = null,
    /** Up to 10 cast faces from TMDB, ordered by billing. */
    val castList: List<TmdbMovieDetailsRepository.CastEntry> = emptyList(),
    /**
     * TMDB's "similar movies" list, intersected with the user's catalogue so only
     * actually-playable titles show up. Replaces the group-title "Meer in X" rail when
     * non-empty.
     */
    val similar: List<Channel> = emptyList(),
    /** Other titles sharing the same groupTitle — the "Meer in X" rail. */
    val related: List<Channel> = emptyList(),
) {
    val hasProgress: Boolean get() = positionMs > 0L && durationMs > 0L
    val progressFraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    /** True when the user has effectively finished the movie — used to offer "afspelen" instead of "hervatten". */
    val watched: Boolean get() = hasProgress && progressFraction >= 0.95f
}

@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailViewModel : ViewModel() {

    private val app = IptvApp.get()
    private val dao = app.database.channelDao()
    private val settings = app.settings
    private val activeProfileIdFlow = app.activeProfileId

    private val _state = MutableStateFlow(MovieDetailState())
    val state: StateFlow<MovieDetailState> = _state.asStateFlow()

    private var loadedId: String? = null

    fun load(channelId: String) {
        if (loadedId == channelId) return
        loadedId = channelId

        // Reset first so the UI doesn't briefly render stale plot/related from the previous
        // film while the new data is still in flight.
        _state.value = MovieDetailState(loading = true)

        viewModelScope.launch {
            val channel = dao.getChannelById(channelId)?.toDomain()
            _state.update { it.copy(channel = channel, loading = false) }
            channel?.let {
                fetchVodInfo(it)
                fetchRelated(it)
                fetchTmdbDetails(it)
            }
        }

        activeProfileIdFlow
            .flatMapLatest { profileId -> dao.observeProgress(profileId, channelId) }
            .onEach { p ->
                _state.update {
                    it.copy(
                        positionMs = p?.positionMs ?: 0L,
                        durationMs = p?.durationMs ?: 0L,
                    )
                }
            }
            .launchIn(viewModelScope)

        activeProfileIdFlow
            .flatMapLatest { profileId -> dao.observeFavoriteIds(profileId) }
            .map { channelId in it }
            .onEach { fav -> _state.update { it.copy(isFavorite = fav) } }
            .launchIn(viewModelScope)
    }

    fun toggleFavorite() {
        val id = loadedId ?: return
        val fav = _state.value.isFavorite
        val profileId = activeProfileIdFlow.value
        viewModelScope.launch {
            if (fav) dao.removeFavorite(profileId, id) else dao.addFavorite(profileId, id)
        }
    }

    private suspend fun fetchVodInfo(channel: Channel) {
        // VOD info is best-effort: it enriches the screen but the core Play/Resume/Add path
        // must work without it. Silently drop failures.
        val config = settings.sourceConfig.first() as? SourceConfig.Xtream ?: return
        val vodId = channel.id.removePrefix("xt-vod:").ifBlank { return }
        runCatching {
            withContext(Dispatchers.IO) {
                val api = HttpClient.retrofitFor(config.host).create(XtreamApi::class.java)
                api.getVodInfo(config.username, config.password, vodId = vodId)
            }
        }.onSuccess { raw ->
            val meta = raw.info ?: return@onSuccess
            _state.update {
                it.copy(
                    plot = meta.plot?.takeIf { s -> s.isNotBlank() },
                    cast = meta.cast?.takeIf { s -> s.isNotBlank() },
                    director = meta.director?.takeIf { s -> s.isNotBlank() },
                    genre = meta.genre?.takeIf { s -> s.isNotBlank() },
                    releaseYear = meta.releaseDate?.take(4)?.takeIf { s -> s.length == 4 && s.all { c -> c.isDigit() } },
                    rating = meta.rating?.takeIf { s -> s.isNotBlank() },
                    country = meta.country?.takeIf { s -> s.isNotBlank() },
                    durationLabel = formatDuration(meta.durationSecs, meta.duration),
                    // If TMDB hasn't populated a trailer yet, use whatever Xtream provided —
                    // the TMDB fetch below will overwrite with an official Trailer when it
                    // wins the search. Providers send either a bare YouTube id or a full
                    // URL; normaliseTrailerUrl handles both.
                    trailerUrl = it.trailerUrl ?: normaliseTrailerUrl(meta.youtubeTrailer),
                )
            }
        }
    }

    /**
     * TMDB enrichment — adds cast faces, a better-curated trailer, and the
     * "Meer zoals dit" rail intersected with the user's catalogue. All best-effort:
     * detail screen keeps working when TMDB is unreachable or unconfigured.
     */
    private suspend fun fetchTmdbDetails(channel: Channel) {
        val app = IptvApp.get()
        // Strip Xtream's country-flag / quality prefixes before handing the title to TMDB's
        // search endpoint — "| EN | Dune Part Two" becomes "dune part two", which actually
        // matches TMDB's canonical title.
        val titleForSearch = TmdbCatalogueMatcher.normalize(channel.name)
            .ifBlank { channel.name }

        // Use the Xtream-derived year when available (it's in _state by the time this runs
        // after fetchVodInfo completes above); fall back to parsing a bare 4-digit year out
        // of the title itself.
        val yearFromState = _state.value.releaseYear?.toIntOrNull()
        val yearFromTitle = YEAR_REGEX.find(channel.name)?.value?.toIntOrNull()
        val year = yearFromState ?: yearFromTitle

        val bundle = runCatching {
            app.tmdbMovieDetails.lookupMovie(
                channelId = channel.id,
                title = titleForSearch,
                releaseYear = year,
            )
        }.getOrNull() ?: return

        // Match TMDB's similar list against the user's catalogue of movies — only show
        // titles they can actually play.
        val matched = if (bundle.similar.isEmpty()) emptyList()
        else withContext(Dispatchers.Default) {
            val userMovies = dao.allChannels().asSequence()
                .map { it.toDomain() }
                .filter { it.type == ContentType.MOVIE && it.id != channel.id }
                .toList()
            MovieMatcher.match(bundle.similar, userMovies)
        }

        val trailer = bundle.trailerYoutubeKey?.let { "https://www.youtube.com/watch?v=$it" }
        _state.update { prev ->
            prev.copy(
                castList = bundle.cast,
                similar = matched,
                // Prefer TMDB's trailer pick over Xtream's when both exist — it's usually
                // the official 2–3 minute trailer rather than a random upload.
                trailerUrl = trailer ?: prev.trailerUrl,
            )
        }
    }

    private suspend fun fetchRelated(channel: Channel) {
        val group = channel.groupTitle ?: return
        val rows = withContext(Dispatchers.Default) {
            dao.getRelatedByGroup(
                type = channel.type.name,
                groupTitle = group,
                excludeId = channel.id,
                limit = 20,
            ).map { it.toDomain() }
        }
        _state.update { it.copy(related = rows) }
    }

    private fun formatDuration(secs: Long?, fallback: String?): String? {
        if (secs != null && secs > 0) {
            val h = secs / 3600
            val m = (secs % 3600) / 60
            return if (h > 0) "${h}u ${m}m" else "${m}m"
        }
        return fallback?.takeIf { it.isNotBlank() }
    }

    /**
     * Xtream's `youtube_trailer` is sometimes a full watch URL, sometimes a bare 11-char
     * YouTube video id. Normalise to a canonical watch URL so the UI can always just fire
     * ACTION_VIEW with it.
     */
    private fun normaliseTrailerUrl(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("http://", ignoreCase = true) ||
            s.startsWith("https://", ignoreCase = true)
        ) return s
        // Bare 11-char ids: letters, digits, '-', '_'.
        if (YOUTUBE_ID_REGEX.matches(s)) return "https://www.youtube.com/watch?v=$s"
        return null
    }

    private companion object {
        val YEAR_REGEX = Regex("\\b(19|20)\\d{2}\\b")
        val YOUTUBE_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")
    }
}
