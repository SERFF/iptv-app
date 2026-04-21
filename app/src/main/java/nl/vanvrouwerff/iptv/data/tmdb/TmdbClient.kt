package nl.vanvrouwerff.iptv.data.tmdb

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import nl.vanvrouwerff.iptv.BuildConfig
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import retrofit2.Retrofit

/**
 * TMDB is an orthogonal service from the user's Xtream source — different host, different
 * auth model (Bearer JWT). Keep it in its own client so Xtream retries / cache policies
 * don't accidentally spill over.
 */
object TmdbClient {

    private const val BASE_URL = "https://api.themoviedb.org/3/"

    /** True if the build has a non-blank token wired through BuildConfig. */
    val isConfigured: Boolean get() = BuildConfig.TMDB_BEARER_TOKEN.isNotBlank()

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${BuildConfig.TMDB_BEARER_TOKEN}")
            .addHeader("Accept", "application/json")
            .build()
        chain.proceed(request)
    }

    private val okHttp by lazy {
        // Piggy-back on the shared OkHttp so we share a connection pool, but stack our own
        // auth interceptor on top.
        HttpClient.okHttp.newBuilder().addInterceptor(authInterceptor).build()
    }

    val api: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttp)
            .addConverterFactory(HttpClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApi::class.java)
    }
}
