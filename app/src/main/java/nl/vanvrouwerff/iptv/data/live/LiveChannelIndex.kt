package nl.vanvrouwerff.iptv.data.live

import kotlinx.coroutines.flow.first
import nl.vanvrouwerff.iptv.data.AdultContent
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.ChannelDao
import nl.vanvrouwerff.iptv.data.db.toDomain

/** A named zap list: the user's favourites or one category. */
data class LiveGroup(val title: String, val channels: List<Channel>)

/**
 * Live-TV lookup shared by the player and the guide: a fixed channel numbering (favourites
 * first in the user's own order, then the rest in catalogue order) and the zap groups
 * (favourites, then every category in catalogue order).
 */
data class LiveChannelIndex(
    val numbered: List<Channel>,
    val numberById: Map<String, Int>,
    val groups: List<LiveGroup>,
    /** True when the first group is the favourites group. */
    val hasFavorites: Boolean,
) {
    companion object {
        suspend fun load(
            dao: ChannelDao,
            profileId: String,
            favoritesLabel: String,
            uncategorizedLabel: String,
            hideAdult: Boolean = false,
        ): LiveChannelIndex {
            val all = AdultContent.filterChannels(
                dao.playableByType(ContentType.TV.name).map { it.toDomain() },
                hideAdult,
            ) { it.groupTitle }
            val byId = all.associateBy { it.id }
            val favorites = dao.favoritesOrdered(profileId).mapNotNull { byId[it.channelId] }
            val favSet = favorites.mapTo(HashSet()) { it.id }
            val numbered = favorites + all.filter { it.id !in favSet }
            val categoryOrder = dao.observeCategoriesByType(ContentType.TV.name).first().map { it.name }
            val grouped = all.groupBy { it.groupTitle ?: uncategorizedLabel }
            val orderedTitles = categoryOrder.filter { it in grouped } +
                grouped.keys.filter { it !in categoryOrder }
            val groups = buildList {
                if (favorites.isNotEmpty()) add(LiveGroup(favoritesLabel, favorites))
                orderedTitles.forEach { title -> add(LiveGroup(title, grouped.getValue(title))) }
            }
            return LiveChannelIndex(
                numbered = numbered,
                numberById = numbered.withIndex().associate { (i, ch) -> ch.id to (i + 1) },
                groups = groups,
                hasFavorites = favorites.isNotEmpty(),
            )
        }
    }
}
