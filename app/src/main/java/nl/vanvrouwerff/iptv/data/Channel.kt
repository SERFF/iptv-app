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
)
