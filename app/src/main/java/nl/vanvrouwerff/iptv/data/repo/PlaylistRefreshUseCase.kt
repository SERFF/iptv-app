package nl.vanvrouwerff.iptv.data.repo

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.data.db.CategoryEntity
import nl.vanvrouwerff.iptv.data.db.ChannelDao
import nl.vanvrouwerff.iptv.data.db.toEntity
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.settings.SettingsStore
import nl.vanvrouwerff.iptv.data.settings.SourceConfig
import nl.vanvrouwerff.iptv.data.xtream.CategoryFilter

/**
 * Single entry point for catalogue refreshes (home screen, settings, nightly worker).
 * Serialised with a mutex so two screens can't run two 2-minute bulk inserts at once;
 * [refreshing] and [lastError] are shared so every screen shows the same status.
 */
class PlaylistRefreshUseCase(
    private val settings: SettingsStore,
    private val dao: ChannelDao,
    private val onCatalogueChanged: () -> Unit = {},
) {

    private val mutex = Mutex()

    private val _refreshing = MutableStateFlow(false)
    /** True while the catalogue (not the EPG) is being fetched and written. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _progress = MutableStateFlow<ImportProgress?>(null)
    /** Stage + count of the running refresh; null when idle. */
    val progress: StateFlow<ImportProgress?> = _progress.asStateFlow()

    /**
     * @param onCatalogueReady invoked as soon as channels + categories are persisted, BEFORE
     *   the EPG write runs, so the UI can drop its spinner while the EPG persists.
     */
    suspend operator fun invoke(
        onCatalogueReady: () -> Unit = {},
    ): Result<Unit> {
        if (!mutex.tryLock()) {
            // Another refresh is already running: wait for it instead of starting a second one.
            mutex.withLock { }
            onCatalogueReady()
            return _lastError.value?.let { Result.failure(IllegalStateException(it)) } ?: Result.success(Unit)
        }
        try {
            _refreshing.value = true
            _lastError.value = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    refresh {
                        _refreshing.value = false
                        onCatalogueReady()
                    }
                }
            }
            result.exceptionOrNull()?.let { err ->
                Log.w(TAG, "Refresh failed", err)
                _lastError.value = err.message?.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
            }
            return result
        } finally {
            _refreshing.value = false
            _progress.value = null
            mutex.unlock()
        }
    }

    private suspend fun refresh(onCatalogueReady: () -> Unit) {
        val config = settings.sourceConfig.first()
            ?: error("Geen bron geconfigureerd.")

        val repo: PlaylistRepository = when (config) {
            is SourceConfig.M3u -> M3uPlaylistRepository(config.url, HttpClient.okHttp)
            is SourceConfig.Xtream -> XtreamPlaylistRepository(
                config.host,
                config.username,
                config.password,
                CategoryFilter.parse(settings.categoryFilter.first()),
            )
        }

        val etag = settings.playlistEtag.first()
        val lastMod = settings.playlistLastModified.first()
        val snapshot = repo.fetch(etag, lastMod) { p -> _progress.value = p }
        if (snapshot.notModified) {
            settings.markRefreshSuccess()
            onCatalogueReady()
            return
        }

        val t0 = SystemClock.elapsedRealtime()
        val channels = snapshot.channels.mapIndexed { i, c -> c.toEntity(i) }
        val categories = snapshot.channels
            .mapNotNull { c -> c.groupTitle?.let { it to c.type.name } }
            .distinct()
            .mapIndexed { i, (name, type) ->
                CategoryEntity(id = name, name = name, sortIndex = i, type = type)
            }
        val t1 = SystemClock.elapsedRealtime()
        Log.i(TAG, "Built ${channels.size} entities + ${categories.size} categories in ${t1 - t0}ms")

        _progress.value = ImportProgress(ImportProgress.Stage.Saving, channels.size)
        dao.replaceAll(channels, categories)
        val t2 = SystemClock.elapsedRealtime()
        Log.i(TAG, "replaceAll persisted ${channels.size} rows in ${t2 - t1}ms")

        // Commit the catalogue + validators now so a crash during the EPG write doesn't
        // force a full re-fetch on next launch.
        settings.savePlaylistValidators(snapshot.etag, snapshot.lastModified)
        settings.markRefreshSuccess()
        onCatalogueChanged()
        onCatalogueReady()

        if (snapshot.programmes.isNotEmpty()) {
            dao.replaceProgrammes(snapshot.programmes)
            val t3 = SystemClock.elapsedRealtime()
            Log.i(TAG, "persisted ${snapshot.programmes.size} EPG programmes in ${t3 - t2}ms")
        }
    }

    private companion object {
        const val TAG = "PlaylistRefresh"
    }
}
