package nl.vanvrouwerff.iptv.data.remote

import android.content.Context
import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import nl.vanvrouwerff.iptv.BuildConfig
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
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
            .addInterceptor(loggingInterceptor())
            .build()
    }

    private fun buildUncached(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor())
        .build()

    private val credentialQuery = Regex("""((?:username|password)=)[^&\s]*""")
    private val credentialPath = Regex("""/(live|movie|series|timeshift)/[^/\s]+/[^/\s]+/""")

    /** Scrubs Xtream credentials from both query strings and stream paths before logging. */
    internal fun redact(line: String): String = line
        .replace(credentialQuery, "$1***")
        .replace(credentialPath, "/$1/***/***/")

    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor { message -> Log.i("OkHttp", redact(message)) }.apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }

    /**
     * Client for media playback: no disk cache (video segments would churn the API cache)
     * and a shorter read timeout so a stalled live stream surfaces as an error quickly.
     */
    val streaming: OkHttpClient by lazy {
        okHttp.newBuilder()
            .cache(null)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun retrofitFor(baseUrl: String): Retrofit {
        val normalised = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalised)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
