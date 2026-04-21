package nl.vanvrouwerff.iptv.data.tmdb

import retrofit2.http.GET
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
}
