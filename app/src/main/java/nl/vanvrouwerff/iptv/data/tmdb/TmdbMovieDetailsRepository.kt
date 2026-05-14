package nl.vanvrouwerff.iptv.data.tmdb

import android.util.Log
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.db.ChannelDao
import nl.vanvrouwerff.iptv.data.db.toDomain

/**
 * Fetches TMDB's full detail bundle for a single movie — trailer key, cast, similar titles —
 * in one [TmdbApi.getMovieDetails] round-trip after an initial search by title/year.
 *
 * Caches results in-memory with a 24h TTL. Persisting across restarts would mean adding a
 * Room entity + migration, and the detail screen is low-traffic enough that re-fetching
 * on a fresh app start isn't meaningfully slower than opening a cached row.
 */
class TmdbMovieDetailsRepository {

    /**
     * LRU map keyed by the Xtream channel id (stable across sessions for a given source).
     * Bounded so a user who browses hundreds of titles in one session doesn't leak heap.
     */
    private val cache: MutableMap<String, CacheEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, /*accessOrder*/ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
                size > MAX_CACHE_ENTRIES
        },
    )

    /**
     * Look up detailed metadata for a movie with the given Xtream channel id, title, and
     * (optional) release year. Returns null when TMDB isn't configured, the search
     * surfaces no hits, or the network is unreachable — the caller is expected to treat
     * null as "just use the Xtream data we already have".
     */
    suspend fun lookupMovie(
        channelId: String,
        title: String,
        releaseYear: Int?,
    ): MovieDetailsBundle? {
        if (!TmdbClient.isConfigured) {
            Log.w(TAG, "TMDB not configured (BuildConfig.TMDB_BEARER_TOKEN is blank)")
            return null
        }
        val now = System.currentTimeMillis()
        val cached = cache[channelId]
        if (cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
            Log.i(TAG, "cache hit for $channelId")
            return cached.bundle
        }

        val bundle = runCatching {
            withContext(Dispatchers.IO) { fetchFresh(title, releaseYear) }
        }.getOrElse {
            Log.w(TAG, "TMDB movie lookup threw for \"$title\"", it)
            return null
        }

        if (bundle != null) {
            cache[channelId] = CacheEntry(bundle, now)
            Log.i(TAG, "cached bundle for $channelId tmdbId=${bundle.tmdbId}")
        } else {
            Log.i(TAG, "fetchFresh returned null for \"$title\" year=$releaseYear")
        }
        return bundle
    }

    private suspend fun fetchFresh(title: String, releaseYear: Int?): MovieDetailsBundle? {
        val query = title.trim()
        Log.i(TAG, "searchMovie query=\"$query\" year=$releaseYear")
        val searchResults = runCatching {
            TmdbClient.api.searchMovie(query = query, year = releaseYear)
        }.onFailure { Log.w(TAG, "searchMovie #1 threw", it) }
            .getOrNull()?.results.orEmpty()
        Log.i(TAG, "searchMovie #1 returned ${searchResults.size} results")

        val candidates = if (searchResults.isNotEmpty()) searchResults
        else {
            val normalised = TmdbCatalogueMatcher.normalize(query)
            if (normalised.isBlank() || normalised == query) {
                emptyList()
            } else {
                Log.i(TAG, "retry searchMovie normalised=\"$normalised\"")
                runCatching {
                    TmdbClient.api.searchMovie(query = normalised, year = releaseYear)
                }.onFailure { Log.w(TAG, "searchMovie retry threw", it) }
                    .getOrNull()?.results.orEmpty()
                    .also { Log.i(TAG, "retry returned ${it.size} results") }
            }
        }

        val hit = candidates.firstOrNull() ?: return null
        Log.i(TAG, "top hit tmdbId=${hit.id} title=\"${hit.title}\" date=${hit.releaseDate}")
        val details = runCatching {
            TmdbClient.api.getMovieDetails(hit.id)
        }.onFailure { Log.w(TAG, "getMovieDetails threw for ${hit.id}", it) }
            .getOrNull() ?: return null

        return MovieDetailsBundle(
            tmdbId = hit.id,
            trailerYoutubeKey = pickTrailerKey(details.videos?.results.orEmpty()),
            cast = details.credits?.cast.orEmpty().asSequence()
                .sortedBy { it.order }
                .take(MAX_CAST)
                .map { m ->
                    CastEntry(
                        id = m.id,
                        name = m.name,
                        character = m.character?.takeIf { it.isNotBlank() },
                        profilePath = m.profilePath,
                    )
                }
                .toList(),
            similar = details.similar?.results.orEmpty(),
        )
    }

    /**
     * Pick the "best" YouTube trailer key out of TMDB's videos list. Preference is:
     * official Trailer > Trailer > official Teaser > Teaser. Falls back to any YouTube
     * item if none match.
     */
    private fun pickTrailerKey(videos: List<TmdbVideoItem>): String? {
        if (videos.isEmpty()) return null
        val youtube = videos.filter { it.site.equals("YouTube", ignoreCase = true) }
        val priority: (TmdbVideoItem) -> Int = { v ->
            val t = v.type.lowercase()
            when {
                t == "trailer" && v.official -> 0
                t == "trailer" -> 1
                t == "teaser" && v.official -> 2
                t == "teaser" -> 3
                else -> 4
            }
        }
        return youtube.minByOrNull(priority)?.key ?: videos.firstOrNull { it.site.equals("YouTube", true) }?.key
    }

    /**
     * Normalised title → channel lookup for the MOVIE catalogue. Built lazily on first
     * use and then reused for every subsequent detail open in this process, avoiding the
     * 40+ second full-table scan + normalisation that was happening per-screen. Mutex
     * prevents two concurrent detail opens both triggering the build.
     */
    @Volatile private var movieIndex: Map<String, Channel>? = null
    private val indexBuildMutex = Mutex()

    /**
     * Match a TMDB "similar" list against the user's movie catalogue. Returns only titles
     * they can actually play, in TMDB order. Uses a cached normalised-title index so the
     * match is O(n) where n = similar.size, not O(catalogue × similar).
     */
    suspend fun matchSimilar(
        similar: List<TmdbMovieItem>,
        excludeChannelId: String,
        dao: ChannelDao,
    ): List<Channel> {
        if (similar.isEmpty()) return emptyList()
        val index = getOrBuildMovieIndex(dao)
        if (index.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        return similar.mapNotNull { item ->
            listOfNotNull(item.title, item.originalTitle)
                .firstNotNullOfOrNull { t ->
                    val key = TmdbCatalogueMatcher.normalize(t)
                    if (key.isEmpty()) null else index[key]
                }
                ?.takeIf { it.id != excludeChannelId && seen.add(it.id) }
        }
    }

    private suspend fun getOrBuildMovieIndex(dao: ChannelDao): Map<String, Channel> {
        movieIndex?.let { return it }
        return indexBuildMutex.withLock {
            // Double-check after acquiring the lock — another caller may have built it
            // while we were waiting.
            movieIndex?.let { return@withLock it }
            withContext(Dispatchers.Default) {
                val started = System.currentTimeMillis()
                val rows = dao.getChannelsByType("MOVIE")
                val index = HashMap<String, Channel>(rows.size)
                rows.forEach { row ->
                    val ch = row.toDomain()
                    val key = TmdbCatalogueMatcher.normalize(ch.name)
                    if (key.isNotEmpty()) index.putIfAbsent(key, ch)
                }
                Log.i(
                    TAG,
                    "built movie index size=${index.size} from ${rows.size} rows " +
                        "in ${System.currentTimeMillis() - started} ms",
                )
                movieIndex = index
                index
            }
        }
    }

    /** Force a rebuild on the next match call — invoked after a catalogue refresh. */
    fun invalidateMovieIndex() {
        movieIndex = null
    }

    data class MovieDetailsBundle(
        val tmdbId: Long,
        /** 11-char YouTube video id, to be wrapped in a YouTube URL at render time. */
        val trailerYoutubeKey: String?,
        val cast: List<CastEntry>,
        val similar: List<TmdbMovieItem>,
    )

    data class CastEntry(
        val id: Long,
        val name: String,
        val character: String?,
        val profilePath: String?,
    )

    private data class CacheEntry(val bundle: MovieDetailsBundle, val fetchedAt: Long)

    companion object {
        private const val TAG = "TmdbMovieDetails"
        private const val CACHE_TTL_MS: Long = 24L * 3_600_000L
        private const val MAX_CACHE_ENTRIES: Int = 80
        /** Netflix tops out around 8–10 faces in the cast strip; anything more is noise. */
        private const val MAX_CAST: Int = 10
    }
}
