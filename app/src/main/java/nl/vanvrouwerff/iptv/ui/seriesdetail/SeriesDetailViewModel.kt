package nl.vanvrouwerff.iptv.ui.seriesdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import nl.vanvrouwerff.iptv.data.db.WatchedEpisodeEntity
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.settings.SourceConfig
import nl.vanvrouwerff.iptv.data.xtream.XtreamApi
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

    fun load(seriesId: String) {
        if (loadedSeriesId == seriesId) return
        loadedSeriesId = seriesId

        val channelId = "xt-series:$seriesId"
        _state.update { it.copy(seriesChannelId = channelId) }

        // Favorite flag: reactive off the shared favorites flow for the active profile.
        activeProfileIdFlow
            .flatMapLatest { profileId -> dao.observeFavoriteIds(profileId) }
            .map { channelId in it }
            .onEach { fav -> _state.update { it.copy(isFavorite = fav) } }
            .launchIn(viewModelScope)

        viewModelScope.launch {
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
                    subscribeToEpisodeProgress(seasons)
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(loading = false, error = err.message ?: "Laden mislukt")
                    }
                },
            )
        }
    }

    private var progressJob: kotlinx.coroutines.Job? = null

    private fun subscribeToEpisodeProgress(seasons: List<SeriesSeason>) {
        progressJob?.cancel()
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
        progressJob = activeProfileIdFlow
            .flatMapLatest { profileId ->
                dao.observeProgressForIds(profileId, allEpisodeIds)
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
                counts.toMap()
            }
            .onEach { counts -> _state.update { it.copy(watchedCountBySeason = counts) } }
            .launchIn(viewModelScope)
    }

    fun selectSeason(seasonNumber: Int) {
        if (_state.value.selectedSeasonNumber == seasonNumber) return
        _state.update { it.copy(selectedSeasonNumber = seasonNumber) }
    }

    /**
     * Persist episode metadata so the "Continue watching" rail can display it later,
     * even after the user leaves the series-detail screen (we don't refetch get_series_info
     * for the home view). Called right before launching the player.
     */
    fun rememberForContinueWatching(episode: Episode) {
        val s = _state.value
        val seriesChannelId = s.seriesChannelId ?: return
        val profileId = activeProfileIdFlow.value
        viewModelScope.launch {
            dao.rememberEpisode(
                WatchedEpisodeEntity(
                    profileId = profileId,
                    episodeId = episode.id,
                    seriesChannelId = seriesChannelId,
                    seriesName = s.title,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    episodeTitle = episode.title,
                    streamUrl = episode.streamUrl,
                    coverUrl = episode.coverUrl ?: s.cover,
                    durationSecs = episode.durationSecs,
                    firstWatchedAt = System.currentTimeMillis(),
                ),
            )
        }
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
        val url = "${config.host}/series/${config.username}/${config.password}/$rawId.$ext"
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
    }
}
