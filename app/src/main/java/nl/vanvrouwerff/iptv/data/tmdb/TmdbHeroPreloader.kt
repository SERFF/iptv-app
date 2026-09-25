package nl.vanvrouwerff.iptv.data.tmdb

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType

/**
 * Warms the [TmdbMovieDetailsRepository] cache ahead of time for the visible hero items so
 * `trailerYoutubeKey` is available the moment the hero carousel needs it (no network stall
 * on rotation).
 *
 * Fire-and-forget: errors are swallowed — the hero falls back to its Ken Burns backdrop when
 * the trailer isn't ready in time.
 */
class TmdbHeroPreloader(
    private val movieDetails: TmdbMovieDetailsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun warm(heroes: List<Channel>) {
        heroes.asSequence()
            .filter { it.type == ContentType.MOVIE }
            .take(MAX_PRELOAD)
            .filter { inFlight.add(it.id) }
            .forEach { ch ->
                scope.launch {
                    try {
                        val title = TmdbCatalogueMatcher.normalize(ch.name).ifBlank { ch.name }
                        runCatching {
                            movieDetails.lookupMovie(channelId = ch.id, title = title, releaseYear = null)
                        }.onFailure { Log.w(TAG, "preload failed for ${ch.id}", it) }
                    } finally {
                        inFlight.remove(ch.id)
                    }
                }
            }
    }

    companion object {
        private const val TAG = "TmdbHeroPreloader"
        private const val MAX_PRELOAD = 5
    }
}
