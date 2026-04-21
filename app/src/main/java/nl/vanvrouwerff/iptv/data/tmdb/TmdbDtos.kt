package nl.vanvrouwerff.iptv.data.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbTvListResponse(
    val page: Int = 1,
    val results: List<TmdbTvItem> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0,
)

/** Subset of the TMDB TV object that's actually useful for title-matching + display. */
@Serializable
data class TmdbTvItem(
    val id: Long,
    val name: String,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double = 0.0,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val overview: String? = null,
)

@Serializable
data class TmdbMovieListResponse(
    val page: Int = 1,
    val results: List<TmdbMovieItem> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0,
)

/** Subset of the TMDB movie object mirroring TmdbTvItem — different fields (title vs name). */
@Serializable
data class TmdbMovieItem(
    val id: Long,
    val title: String,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val popularity: Double = 0.0,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val overview: String? = null,
)
