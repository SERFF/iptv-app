package nl.vanvrouwerff.iptv.ui.seriesdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.data.db.SeriesInfoCacheEntity
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.settings.SourceConfig
import nl.vanvrouwerff.iptv.data.xtream.XtreamApi
import nl.vanvrouwerff.iptv.data.xtream.XtreamUrls
import nl.vanvrouwerff.iptv.data.xtream.XtreamEpisode
import nl.vanvrouwerff.iptv.data.xtream.XtreamSeriesInfoResponse

data class Episode(
    /** Unique ID across app: "xt-episode:{episodeId}". */
    val id: String,
    val rawId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val streamUrl: String,
    val coverUrl: String?,
    val plot: String?,
    val durationSecs: Long,
    /** Original air date in ISO "yyyy-MM-dd" form — displayed on the row when present. */
    val airDate: String? = null,
)

/** Minimal series context the player needs to record auto-advanced episodes. */
data class SeriesRef(
    val channelId: String,
    val name: String,
    val cover: String?,
)

data class NextUp(
    val episode: Episode,
    val season: SeriesSeason,
    val resumeMs: Long,
    /** True when the episode has saved progress to continue from. */
    val isResume: Boolean,
)

data class SeriesSeason(
    val number: Int,
    val label: String,
    val episodes: List<Episode>,
)

data class SeriesDetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val seriesChannelId: String? = null,
    val title: String = "",
    val cover: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val rating: String? = null,
    /** 4-digit release year extracted from the series meta, when present. */
    val releaseYear: String? = null,
    val seasons: List<SeriesSeason> = emptyList(),
    val selectedSeasonNumber: Int? = null,
    val isFavorite: Boolean = false,
    /**
     * Per-season "watched episode" count. Keyed by season number. An episode counts as
     * watched once its saved progress fraction crosses WATCHED_FRACTION. Used by the season
     * chip row to render a "4 van 10" progress subtitle so the user can see at a glance
     * how far they are into each season.
     */
    val watchedCountBySeason: Map<Int, Int> = emptyMap(),
    /** What the primary button plays: the episode in progress, or the next one to watch. */
    val nextUp: NextUp? = null,
) {
    val selectedSeason: SeriesSeason?
        get() = seasons.firstOrNull { it.number == selectedSeasonNumber } ?: seasons.firstOrNull()
}

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesDetailViewModel : ViewModel() {

    private val app = IptvApp.get()
    private val dao = app.database.channelDao()
    private val activeProfileIdFlow = app.activeProfileId

    private val _state = MutableStateFlow(SeriesDetailState())
    val state: StateFlow<SeriesDetailState> = _state.asStateFlow()

    private var loadedSeriesId: String? = null

    /** Scope of the current [load]; cancelled on the next load so stale results can't land. */
    private var loadScope: CoroutineScope? = null

    fun load(seriesId: String, preview: nl.vanvrouwerff.iptv.data.Channel? = null) {
        if (loadedSeriesId == seriesId) return
        loadedSeriesId = seriesId

        loadScope?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        loadScope = scope

        val channelId = "xt-series:$seriesId"
        val seed = preview?.takeIf { it.id == channelId }
        _state.value = SeriesDetailState(
            seriesChannelId = channelId,
            title = seed?.name.orEmpty(),
            cover = seed?.logoUrl,
        )
        userPickedSeason = false

        // Favorite flag: reactive off the shared favorites flow for the active profile.
        activeProfileIdFlow
            .flatMapLatest { profileId -> dao.observeFavoriteIds(profileId) }
            .map { channelId in it }
            .onEach { fav -> _state.update { it.copy(isFavorite = fav) } }
            .launchIn(scope)

        scope.launch {
            val fallback = dao.getChannelById(channelId)
            _state.update {
                it.copy(
                    title = fallback?.name ?: it.title,
                    cover = fallback?.logoUrl ?: it.cover,
                )
            }

            val config = app.settings.sourceConfig.first() as? SourceConfig.Xtream
            if (config == null) {
                _state.update { it.copy(loading = false, error = "Geen Xtream-bron.") }
                return@launch
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val raw = loadSeriesInfoCachedOrFetch(seriesId, config)
                    buildSeasons(raw, config)
                }
            }

            result.fold(
                onSuccess = { (meta, seasons) ->
                    _state.update { prev ->
                        prev.copy(
                            loading = false,
                            error = null,
                            title = meta?.name?.takeIf { it.isNotBlank() } ?: prev.title,
                            cover = meta?.cover?.takeIf { it.isNotBlank() } ?: prev.cover,
                            plot = meta?.plot?.takeIf { it.isNotBlank() },
                            genre = meta?.genre?.takeIf { it.isNotBlank() },
                            rating = meta?.rating?.takeIf { it.isNotBlank() },
                            releaseYear = meta?.releaseDate?.take(4)
                                ?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } },
                            seasons = seasons,
                            selectedSeasonNumber = seasons.firstOrNull()?.number,
                        )
                    }
                    subscribeToEpisodeProgress(seasons, scope)
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(loading = false, error = err.message ?: "Laden mislukt")
                    }
                },
            )
        }
    }

    private fun subscribeToEpisodeProgress(seasons: List<SeriesSeason>, scope: CoroutineScope) {
        val allEpisodeIds = seasons.flatMap { s -> s.episodes.map { it.id } }
        if (allEpisodeIds.isEmpty()) {
            _state.update { it.copy(watchedCountBySeason = emptyMap()) }
            return
        }
        // Bucket episode IDs per season once — flowOn filters row-level progress into
        // watched counts using these buckets on every Room emission.
        val idToSeason: Map<String, Int> = buildMap {
            seasons.forEach { s -> s.episodes.forEach { put(it.id, s.number) } }
        }
        activeProfileIdFlow
            .flatMapLatest { profileId ->
                val chunks = allEpisodeIds.chunked(PROGRESS_QUERY_CHUNK)
                    .map { dao.observeProgressForIds(profileId, it) }
                combine(chunks) { parts -> parts.flatMap { it } }
            }
            .map { rows ->
                val counts = mutableMapOf<Int, Int>()
                rows.forEach { row ->
                    if (row.durationMs <= 0L) return@forEach
                    val fraction = row.positionMs.toFloat() / row.durationMs
                    if (fraction < WATCHED_FRACTION) return@forEach
                    val season = idToSeason[row.channelId] ?: return@forEach
                    counts[season] = (counts[season] ?: 0) + 1
                }
                counts.toMap() to computeNextUp(seasons, rows)
            }
            .onEach { (counts, nextUp) ->
                _state.update { st ->
                    st.copy(
                        watchedCountBySeason = counts,
                        nextUp = nextUp,
                        // Open on the season the user is in, until they pick one themselves.
                        selectedSeasonNumber = if (!userPickedSeason && nextUp != null) {
                            nextUp.season.number
                        } else st.selectedSeasonNumber,
                    )
                }
            }
            .launchIn(scope)
    }

    private var userPickedSeason = false

    fun selectSeason(seasonNumber: Int) {
        userPickedSeason = true
        if (_state.value.selectedSeasonNumber == seasonNumber) return
        _state.update { it.copy(selectedSeasonNumber = seasonNumber) }
    }

    fun toggleFavorite() {
        val id = _state.value.seriesChannelId ?: return
        val isFav = _state.value.isFavorite
        val profileId = activeProfileIdFlow.value
        viewModelScope.launch {
            if (isFav) dao.removeFavorite(profileId, id) else dao.addFavorite(profileId, id)
        }
    }

    private suspend fun loadSeriesInfoCachedOrFetch(
        seriesId: String,
        config: SourceConfig.Xtream,
    ): XtreamSeriesInfoResponse {
        val now = System.currentTimeMillis()
        val cache = dao.getSeriesInfoCache(seriesId)
        if (cache != null && now - cache.fetchedAt < SERIES_CACHE_TTL_MS) {
            runCatching {
                return HttpClient.json.decodeFromString(
                    XtreamSeriesInfoResponse.serializer(),
                    cache.payloadJson,
                )
            }
            // Fall through to a fresh fetch if the cached payload is somehow corrupt.
        }

        val api = HttpClient.retrofitFor(config.host).create(XtreamApi::class.java)
        val raw = api.getSeriesInfo(config.username, config.password, seriesId = seriesId)
        val payload = runCatching {
            HttpClient.json.encodeToString(XtreamSeriesInfoResponse.serializer(), raw)
        }.getOrNull()
        if (payload != null) {
            dao.putSeriesInfoCache(
                SeriesInfoCacheEntity(
                    seriesId = seriesId,
                    payloadJson = payload,
                    fetchedAt = now,
                ),
            )
        }
        return raw
    }

    private fun buildSeasons(
        raw: nl.vanvrouwerff.iptv.data.xtream.XtreamSeriesInfoResponse,
        config: SourceConfig.Xtream,
    ): Pair<nl.vanvrouwerff.iptv.data.xtream.XtreamSeriesInfoMeta?, List<SeriesSeason>> {
        val episodesJson = raw.episodes
        val groupedByKey: Map<String, List<XtreamEpisode>> = when (episodesJson) {
            is JsonObject -> episodesJson.entries.associate { (season, arr) ->
                season to HttpClient.json.decodeFromJsonElement(
                    ListSerializer(XtreamEpisode.serializer()),
                    arr,
                )
            }
            else -> emptyMap()
        }

        // Map season-number-as-string to domain seasons. Sort ascending by number, episodes
        // within each season sorted by episode number too — providers aren't consistent.
        val seasonMetaByNumber = raw.seasons.associateBy { s -> s.seasonNumber.asInt(0) }
        val seasons = groupedByKey.mapNotNull { (seasonKey, episodes) ->
            val seasonNum = seasonKey.toIntOrNull() ?: return@mapNotNull null
            val label = seasonMetaByNumber[seasonNum]?.name?.takeIf { it.isNotBlank() }
                ?: "Seizoen $seasonNum"
            val mapped = episodes.mapNotNull { ep -> mapEpisode(ep, seasonNum, config) }
                .sortedBy { it.episodeNumber }
            SeriesSeason(number = seasonNum, label = label, episodes = mapped)
        }.sortedBy { it.number }

        return raw.info to seasons
    }

    private fun mapEpisode(
        ep: XtreamEpisode,
        seasonNumber: Int,
        config: SourceConfig.Xtream,
    ): Episode? {
        val rawId = ep.id.asScalarString().ifBlank { return null }
        val ext = ep.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        val url = XtreamUrls.stream(config.host, "series", config.username, config.password, "$rawId.$ext")
        val epNum = ep.episodeNum?.asInt(0) ?: 0
        val title = ep.title?.takeIf { it.isNotBlank() } ?: "Aflevering $epNum"
        return Episode(
            id = "xt-episode:$rawId",
            rawId = rawId,
            seasonNumber = seasonNumber,
            episodeNumber = epNum,
            title = title,
            streamUrl = url,
            coverUrl = ep.info?.movieImage?.takeIf { it.isNotBlank() },
            plot = ep.info?.plot?.takeIf { it.isNotBlank() },
            durationSecs = ep.info?.durationSecs ?: 0L,
            airDate = (ep.info?.airDate ?: ep.info?.releaseDate)?.takeIf { it.isNotBlank() },
        )
    }

    private fun JsonElement.asScalarString(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: toString().trim('"')

    private fun JsonElement?.asInt(default: Int): Int {
        val s = (this as? JsonPrimitive)?.contentOrNull ?: return default
        return s.toIntOrNull() ?: default
    }

    private companion object {
        const val SERIES_CACHE_TTL_MS: Long = 24L * 3_600_000L
        /** Mirrors the screen-side constant: an episode counts as "bekeken" at 95 %. */
        const val WATCHED_FRACTION = 0.95f
        const val PROGRESS_QUERY_CHUNK = 500
    }
}

/**
 * The episode the primary button should play. The most recently touched episode wins: if
 * it isn't (nearly) finished we resume it, otherwise the following episode in broadcast
 * order starts from the top. No progress at all → the very first episode.
 */
internal fun computeNextUp(
    seasons: List<SeriesSeason>,
    progress: List<nl.vanvrouwerff.iptv.data.db.WatchProgressEntity>,
): NextUp? {
    val ordered = seasons.flatMap { s -> s.episodes.map { it to s } }
    if (ordered.isEmpty()) return null
    val latest = progress
        .filter { it.positionMs > 0L }
        .maxByOrNull { it.updatedAt }
    val first = ordered.first()
    if (latest == null) return NextUp(first.first, first.second, 0L, isResume = false)
    val index = ordered.indexOfFirst { it.first.id == latest.channelId }
    if (index < 0) return NextUp(first.first, first.second, 0L, isResume = false)
    val finished = latest.durationMs > 0L &&
        latest.positionMs.toFloat() / latest.durationMs >= NEXT_UP_WATCHED_FRACTION
    if (!finished) {
        val (ep, season) = ordered[index]
        return NextUp(ep, season, latest.positionMs, isResume = true)
    }
    val next = ordered.getOrNull(index + 1) ?: first
    return NextUp(next.first, next.second, 0L, isResume = false)
}

private const val NEXT_UP_WATCHED_FRACTION = 0.95f
