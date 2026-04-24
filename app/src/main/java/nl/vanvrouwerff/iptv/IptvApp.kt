package nl.vanvrouwerff.iptv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.vanvrouwerff.iptv.data.db.IptvDatabase
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.repo.PlaylistRefreshScheduler
import nl.vanvrouwerff.iptv.data.repo.PlaylistRefreshUseCase
import nl.vanvrouwerff.iptv.data.settings.SettingsStore
import nl.vanvrouwerff.iptv.data.tmdb.TmdbMovieDetailsRepository
import nl.vanvrouwerff.iptv.data.tmdb.TmdbPopularRepository

/**
 * Poor man's DI. For a weekend-sized project a handful of lazy singletons beats
 * pulling in Hilt and its compiler round-trip. Swap later if it stops fitting.
 */
class IptvApp : Application(), ImageLoaderFactory {

    val settings: SettingsStore by lazy { SettingsStore(this) }
    val database: IptvDatabase by lazy { IptvDatabase.get(this) }
    val refreshUseCase: PlaylistRefreshUseCase by lazy {
        PlaylistRefreshUseCase(settings, database.channelDao())
    }
    val tmdbPopular: TmdbPopularRepository by lazy {
        TmdbPopularRepository(database.channelDao())
    }
    val tmdbMovieDetails: TmdbMovieDetailsRepository by lazy {
        TmdbMovieDetailsRepository()
    }

    /**
     * App-scoped coroutine context for long-lived collectors — the one below keeps the
     * active-profile StateFlow hot so any `.value` read elsewhere returns immediately
     * instead of blocking on a first-emit from DataStore.
     */
    private val appScope = CoroutineScope(SupervisorJob())

    /**
     * Active profile id as a hot StateFlow. Seeded with the built-in "default" id so
     * the very first read (which can happen before DataStore has emitted anything)
     * resolves to a sensible, existing profile row instead of null.
     */
    val activeProfileId: StateFlow<String> by lazy {
        settings.activeProfileId.stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = IptvDatabase.DEFAULT_PROFILE_ID,
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Must wire up OkHttp before anyone touches it — repositories grab it via
        // HttpClient.okHttp for Retrofit construction, and Coil below reuses the same
        // instance so connection pools aren't fragmented.
        HttpClient.init(this)

        // Apply the nightly-refresh schedule on every app start AND reapply whenever the
        // user toggles the switch or changes the hour. Distinct-until-changed means that
        // incidental DataStore emissions don't re-enqueue the worker unnecessarily.
        appScope.launch {
            combine(settings.autoRefreshEnabled, settings.autoRefreshHour, ::Pair)
                .distinctUntilChanged()
                .collect { (enabled, hour) ->
                    PlaylistRefreshScheduler.apply(this@IptvApp, enabled, hour)
                }
        }
    }

    /**
     * Explicit Coil configuration. Defaults allocate ~15% of heap to memory cache
     * unbounded in absolute terms — on a 4 GB Formuler that can mean 300+ MB of poster
     * bitmaps squatting on the heap. We cap it at 64 MB (enough for one full screen
     * plus one look-ahead rail of posters) and 200 MB on disk so re-scrolling a rail
     * is instant. Shares the app's OkHttp so the connection pool is one, not two.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { HttpClient.okHttp }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizeBytes(64 * 1024 * 1024)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    companion object {
        private lateinit var instance: IptvApp
        fun get(): IptvApp = instance
    }
}
