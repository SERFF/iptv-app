package nl.vanvrouwerff.iptv.data.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    /**
     * Currently popular TV shows on TMDB. Good default for a home rail. `trending/tv/week`
     * is slightly more "what's hot right now" — swap the path if we want that later.
     */
    @GET("tv/popular")
    suspend fun getPopularTv(
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
    ): TmdbTvListResponse

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
    ): TmdbMovieListResponse

    /**
     * Search by title — we narrow by [year] when the Xtream VOD info gave us a release
     * year, which drastically improves first-result accuracy for common titles
     * ("Avatar", "Titanic"...).
     */
    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("year") year: Int? = null,
        @Query("language") language: String = "en-US",
        @Query("include_adult") includeAdult: Boolean = false,
    ): TmdbMovieListResponse

    /**
     * One-shot lookup for a movie's full detail page. `append_to_response` pulls credits,
     * similar titles, and trailer videos in a single HTTP round-trip so the detail screen
     * only pays for one latency hit.
     */
    @GET("movie/{id}")
    suspend fun getMovieDetails(
        @Path("id") id: Long,
        @Query("language") language: String = "en-US",
        @Query("append_to_response") append: String = "credits,similar,videos",
    ): TmdbMovieDetailsResponse
}
