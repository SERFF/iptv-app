package nl.vanvrouwerff.iptv.data.remote

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object HttpClient {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Holds the lazily-constructed OkHttp instance. Initialised via [init] from IptvApp so the
     * disk cache can live under the app's cache dir. Falls back to an un-cached builder if
     * anything touches `okHttp` before init — we never want a NPE to crash feature code.
     */
    @Volatile private var _okHttp: OkHttpClient? = null

    val okHttp: OkHttpClient
        get() = _okHttp ?: buildUncached().also { _okHttp = it }

    fun init(context: Context) {
        if (_okHttp != null) return
        val cacheDir = File(context.cacheDir, "http").apply { mkdirs() }
        _okHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            // 20 MB disk-cache for API responses (get_series_info, get_vod_info). Saves a
            // full round-trip every time the user re-opens a detail they've seen recently.
            .cache(Cache(cacheDir, 20L * 1024 * 1024))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }

    private fun buildUncached(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    fun retrofitFor(baseUrl: String): Retrofit {
        val normalised = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalised)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
