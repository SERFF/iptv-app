package nl.vanvrouwerff.iptv.data

enum class ContentType { TV, MOVIE, SERIES }

data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val streamUrl: String?,
    val epgChannelId: String?,
    val type: ContentType,
    /** Days of catch-up archive the provider keeps for this live channel; 0 = none. */
    val archiveDays: Int = 0,
)
