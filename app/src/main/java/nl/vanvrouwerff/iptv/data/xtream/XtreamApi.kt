package nl.vanvrouwerff.iptv.data.xtream

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

interface XtreamApi {

    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories",
    ): List<XtreamCategory>

    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
    ): List<XtreamLiveStream>

    @GET("player_api.php")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories",
    ): List<XtreamCategory>

    // VOD and series payloads can be tens of megabytes on big providers. Keep them
    // streaming and decode via InputStream so we never hold the whole body as a String.
    @Streaming
    @GET("player_api.php")
    suspend fun getVodStreamsStream(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams",
    ): ResponseBody

    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories",
    ): List<XtreamCategory>

    @Streaming
    @GET("player_api.php")
    suspend fun getSeriesStream(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series",
    ): ResponseBody

    // Single-series info: seasons + episodes. Response is small enough not to need streaming.
    @GET("player_api.php")
    suspend fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("series_id") seriesId: String,
        @Query("action") action: String = "get_series_info",
    ): XtreamSeriesInfoResponse

    // Single-movie info: plot, cast, rating, duration. Small response.
    @GET("player_api.php")
    suspend fun getVodInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("vod_id") vodId: String,
        @Query("action") action: String = "get_vod_info",
    ): XtreamVodInfoResponse

    // XMLTV EPG. Usually gzipped by the server; OkHttp decompresses transparently.
    // Feeds can run 10-50 MB, so always stream.
    @Streaming
    @GET("xmltv.php")
    suspend fun getXmltv(
        @Query("username") username: String,
        @Query("password") password: String,
    ): ResponseBody
}
