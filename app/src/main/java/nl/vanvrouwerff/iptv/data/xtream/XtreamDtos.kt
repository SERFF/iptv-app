package nl.vanvrouwerff.iptv.data.xtream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class XtreamCategory(
    @SerialName("category_id") val categoryId: JsonElement,
    @SerialName("category_name") val categoryName: String,
)

@Serializable
data class XtreamLiveStream(
    @SerialName("stream_id") val streamId: JsonElement,
    val name: String,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null,
)

@Serializable
data class XtreamVodStream(
    @SerialName("stream_id") val streamId: JsonElement,
    val name: String,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
)

@Serializable
data class XtreamSeries(
    @SerialName("series_id") val seriesId: JsonElement,
    val name: String,
    val cover: String? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null,
)

@Serializable
data class XtreamSeriesInfoResponse(
    val info: XtreamSeriesInfoMeta? = null,
    val seasons: List<XtreamSeason> = emptyList(),
    // Providers return this either as `{"1": [...], "2": [...]}` (JsonObject) or as
    // `[]` when empty. Keep as JsonElement and decode per-season in the repo layer.
    val episodes: JsonElement? = null,
)

@Serializable
data class XtreamSeriesInfoMeta(
    val name: String? = null,
    val cover: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    val rating: String? = null,
)

@Serializable
data class XtreamSeason(
    @SerialName("season_number") val seasonNumber: JsonElement? = null,
    val name: String? = null,
    val cover: String? = null,
    @SerialName("episode_count") val episodeCount: JsonElement? = null,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
data class XtreamEpisode(
    val id: JsonElement,
    @SerialName("episode_num") val episodeNum: JsonElement? = null,
    val title: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    val info: XtreamEpisodeInfo? = null,
)

@Serializable
data class XtreamEpisodeInfo(
    @SerialName("duration_secs") val durationSecs: Long? = null,
    val duration: String? = null,
    val plot: String? = null,
    @SerialName("movie_image") val movieImage: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    // Alternate spelling used by some Xtream providers.
    @SerialName("releasedate") val releaseDate: String? = null,
)

@Serializable
data class XtreamVodInfoResponse(
    val info: XtreamVodInfoMeta? = null,
    @SerialName("movie_data") val movieData: XtreamVodMovieData? = null,
)

@Serializable
data class XtreamVodInfoMeta(
    val name: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    @SerialName("releasedate") val releaseDate: String? = null,
    val rating: String? = null,
    @SerialName("movie_image") val movieImage: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("backdrop_path") val backdropPath: JsonElement? = null,
    @SerialName("duration_secs") val durationSecs: Long? = null,
    val duration: String? = null,
    @SerialName("youtube_trailer") val youtubeTrailer: String? = null,
    val country: String? = null,
)

@Serializable
data class XtreamVodMovieData(
    @SerialName("stream_id") val streamId: JsonElement? = null,
    val name: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
)
