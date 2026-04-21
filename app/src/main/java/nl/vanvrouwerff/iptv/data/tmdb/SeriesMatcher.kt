package nl.vanvrouwerff.iptv.data.tmdb

import nl.vanvrouwerff.iptv.data.Channel

/**
 * Series-specific wrapper around [TmdbCatalogueMatcher]. Extracts the localised name and
 * the original-language name as match candidates — both are commonly how Xtream providers
 * title their series rows.
 */
object SeriesMatcher {
    fun match(tmdb: List<TmdbTvItem>, xtreamSeries: List<Channel>): List<Channel> =
        TmdbCatalogueMatcher.match(
            tmdb = tmdb,
            titles = { listOfNotNull(it.name, it.originalName) },
            catalogue = xtreamSeries,
        )
}
