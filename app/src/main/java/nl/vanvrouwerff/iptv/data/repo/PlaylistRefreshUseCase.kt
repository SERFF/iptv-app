package nl.vanvrouwerff.iptv.data.repo

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.data.db.CategoryEntity
import nl.vanvrouwerff.iptv.data.db.ChannelDao
import nl.vanvrouwerff.iptv.data.db.toEntity
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.settings.SettingsStore
import nl.vanvrouwerff.iptv.data.settings.SourceConfig

class PlaylistRefreshUseCase(
    private val settings: SettingsStore,
    private val dao: ChannelDao,
) {

    /**
     * @param onCatalogueReady invoked as soon as channels + categories are persisted, BEFORE
     *   the EPG write runs. On the Formuler the channel write takes ~2 min and the EPG
     *   another ~2 min; waiting for both would leave the "Vernieuwen…" spinner on for four
     *   minutes. Firing this callback early lets the UI drop the refreshing state while the
     *   EPG persists in the background.
     */
    suspend operator fun invoke(
        onCatalogueReady: () -> Unit = {},
    ): Result<Unit> = runCatching {
        val config = settings.sourceConfig.first()
            ?: error("Geen bron geconfigureerd.")

        val repo: PlaylistRepository = when (config) {
            is SourceConfig.M3u -> M3uPlaylistRepository(config.url, HttpClient.okHttp)
            is SourceConfig.Xtream -> XtreamPlaylistRepository(
                config.host, config.username, config.password,
            )
        }

        val etag = settings.playlistEtag.first()
        val lastMod = settings.playlistLastModified.first()
        val snapshot = repo.fetch(etag, lastMod)
        if (snapshot.notModified) {
            onCatalogueReady()
            return@runCatching
        }

        // All of the entity building and DB writes go to Default/IO so we don't block
        // the main thread when the catalogue is 200k+ items.
        withContext(Dispatchers.Default) {
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

            dao.replaceAll(channels, categories)
            val t2 = SystemClock.elapsedRealtime()
            Log.i(TAG, "replaceAll persisted ${channels.size} rows in ${t2 - t1}ms")

            // Commit the catalogue + validators now so a crash during the EPG write doesn't
            // force a full re-fetch on next launch.
            settings.savePlaylistValidators(snapshot.etag, snapshot.lastModified)
            settings.markRefreshSuccess()
            onCatalogueReady()

            if (snapshot.programmes.isNotEmpty()) {
                dao.replaceProgrammes(snapshot.programmes)
                val t3 = SystemClock.elapsedRealtime()
                Log.i(TAG, "persisted ${snapshot.programmes.size} EPG programmes in ${t3 - t2}ms")
            }
        }
    }

    private companion object {
        const val TAG = "PlaylistRefresh"
    }
}
