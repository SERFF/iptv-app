package nl.vanvrouwerff.iptv.data.tmdb

import nl.vanvrouwerff.iptv.data.Channel

/**
 * Movie-specific wrapper around [TmdbCatalogueMatcher]. Extracts title + originalTitle —
 * TMDB's movie objects use `title` / `original_title` where TV uses `name` / `original_name`.
 */
object MovieMatcher {
    fun match(tmdb: List<TmdbMovieItem>, xtreamMovies: List<Channel>): List<Channel> =
        TmdbCatalogueMatcher.match(
            tmdb = tmdb,
            titles = { listOfNotNull(it.title, it.originalTitle) },
            catalogue = xtreamMovies,
        )
}
