package nl.vanvrouwerff.iptv.data.tmdb

import android.util.Log
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        if (!TmdbClient.isConfigured) return null
        val now = System.currentTimeMillis()
        val cached = cache[channelId]
        if (cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
            return cached.bundle
        }

        val bundle = runCatching {
            withContext(Dispatchers.IO) { fetchFresh(title, releaseYear) }
        }.getOrElse {
            Log.w(TAG, "TMDB movie lookup failed for \"$title\"", it)
            return null
        }

        if (bundle != null) cache[channelId] = CacheEntry(bundle, now)
        return bundle
    }

    private suspend fun fetchFresh(title: String, releaseYear: Int?): MovieDetailsBundle? {
        // Feed TMDB the raw title first. If that returns nothing, retry with the matcher's
        // normalised form — which strips Xtream quality/country prefixes that would
        // otherwise confuse TMDB's search.
        val query = title.trim()
        val searchResults = runCatching {
            TmdbClient.api.searchMovie(query = query, year = releaseYear)
        }.getOrNull()?.results.orEmpty()

        val candidates = if (searchResults.isNotEmpty()) searchResults
        else {
            val normalised = TmdbCatalogueMatcher.normalize(query)
            if (normalised.isBlank() || normalised == query) emptyList()
            else runCatching {
                TmdbClient.api.searchMovie(query = normalised, year = releaseYear)
            }.getOrNull()?.results.orEmpty()
        }

        val hit = candidates.firstOrNull() ?: return null
        val details = runCatching {
            TmdbClient.api.getMovieDetails(hit.id)
        }.getOrNull() ?: return null

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
