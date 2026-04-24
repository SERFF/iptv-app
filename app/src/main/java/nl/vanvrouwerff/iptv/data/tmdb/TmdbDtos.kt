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

/**
 * Full detail payload for a single movie. `append_to_response=credits,similar,videos`
 * folds the three sub-resources into this object so the detail screen hydrates after a
 * single HTTP call.
 */
@Serializable
data class TmdbMovieDetailsResponse(
    val id: Long,
    val title: String,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    val credits: TmdbCreditsResponse? = null,
    val similar: TmdbMovieListResponse? = null,
    val videos: TmdbVideosResponse? = null,
)

@Serializable
data class TmdbCreditsResponse(
    val cast: List<TmdbCastMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList(),
)

@Serializable
data class TmdbCastMember(
    val id: Long,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 999,
)

@Serializable
data class TmdbCrewMember(
    val id: Long,
    val name: String,
    val job: String? = null,
    val department: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbVideosResponse(
    val results: List<TmdbVideoItem> = emptyList(),
)

@Serializable
data class TmdbVideoItem(
    val id: String,
    val key: String,
    val name: String,
    val site: String,
    val type: String,
    val official: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
)
