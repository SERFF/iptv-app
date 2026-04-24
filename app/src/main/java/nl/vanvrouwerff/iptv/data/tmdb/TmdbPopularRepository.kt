package nl.vanvrouwerff.iptv.data.tmdb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import nl.vanvrouwerff.iptv.data.db.ChannelDao
import nl.vanvrouwerff.iptv.data.db.TmdbPopularCacheEntity
import nl.vanvrouwerff.iptv.data.remote.HttpClient

/**
 * Thin repository wrapping the TMDB "popular" lists. Caches the parsed items in Room
 * with a 24h TTL so we hit the network at most once per day per list key. Silent fallback
 * to empty list when the build isn't configured with a token, so the rest of the app
 * keeps working in developer builds.
 */
class TmdbPopularRepository(private val dao: ChannelDao) {

    suspend fun getPopularTv(): List<TmdbTvItem> {
        if (!TmdbClient.isConfigured) return emptyList()
        return readCacheOrFetch(KEY_POPULAR_TV, TmdbTvItem.serializer()) {
            TmdbClient.api.getPopularTv(language = DEFAULT_LANGUAGE, page = 1).results
        }
    }

    suspend fun getPopularMovies(): List<TmdbMovieItem> {
        if (!TmdbClient.isConfigured) return emptyList()
        return readCacheOrFetch(KEY_POPULAR_MOVIES, TmdbMovieItem.serializer()) {
            TmdbClient.api.getPopularMovies(language = DEFAULT_LANGUAGE, page = 1).results
        }
    }

    private suspend fun <T> readCacheOrFetch(
        key: String,
        serializer: KSerializer<T>,
        fetcher: suspend () -> List<T>,
    ): List<T> = withContext(Dispatchers.IO) {
        val listSerializer = ListSerializer(serializer)
        val now = System.currentTimeMillis()
        val cached = dao.getTmdbPopularCache(key)
        if (cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
            runCatching {
                return@withContext HttpClient.json.decodeFromString(listSerializer, cached.payloadJson)
            }
            // Corrupt cache → fall through to a fresh fetch.
        }

        runCatching {
            val fresh = fetcher()
            val payload = HttpClient.json.encodeToString(listSerializer, fresh)
            dao.putTmdbPopularCache(
                TmdbPopularCacheEntity(cacheKey = key, payloadJson = payload, fetchedAt = now),
            )
            fresh
        }.onFailure { Log.w(TAG, "TMDB fetch failed for $key", it) }
            .getOrElse {
                // Network down but we have some cache — even if stale, return it rather
                // than showing an empty rail.
                cached?.let {
                    runCatching {
                        HttpClient.json.decodeFromString(listSerializer, it.payloadJson)
                    }.getOrNull()
                } ?: emptyList()
            }
    }

    /**
     * Matched-rail cache: channel IDs that survived matching TMDB popular titles against
     * the user's catalogue. Stored separately from the raw TMDB response so the UI can
     * paint the "Populair nu" rail at cold start without waiting on catalogue load +
     * 32k-row title normalisation. Shares the 24h TTL of the underlying TMDB data.
     */
    data class MatchedEntry(val ids: List<String>, val fetchedAt: Long)

    suspend fun readMatchedIds(key: String): MatchedEntry? = withContext(Dispatchers.IO) {
        val entry = dao.getTmdbPopularCache(key) ?: return@withContext null
        val ids = runCatching {
            HttpClient.json.decodeFromString(ListSerializer(String.serializer()), entry.payloadJson)
        }.getOrElse { return@withContext null }
        MatchedEntry(ids, entry.fetchedAt)
    }

    suspend fun writeMatchedIds(key: String, ids: List<String>) = withContext(Dispatchers.IO) {
        val payload = HttpClient.json.encodeToString(ListSerializer(String.serializer()), ids)
        dao.putTmdbPopularCache(
            TmdbPopularCacheEntity(
                cacheKey = key,
                payloadJson = payload,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun isFresh(entry: MatchedEntry): Boolean =
        System.currentTimeMillis() - entry.fetchedAt < CACHE_TTL_MS

    companion object {
        const val KEY_MATCHED_POPULAR_MOVIES = "matched_popular_movies"
        const val KEY_MATCHED_POPULAR_SERIES = "matched_popular_series"

        private const val TAG = "TmdbPopular"
        private const val KEY_POPULAR_TV = "popular_tv"
        private const val KEY_POPULAR_MOVIES = "popular_movies"
        private const val CACHE_TTL_MS: Long = 24L * 3_600_000L
        private const val DEFAULT_LANGUAGE = "en-US"
    }
}
