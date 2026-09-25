package nl.vanvrouwerff.iptv.data

/** Categories hidden in kids profiles. */
object AdultContent {

    private val keywords = listOf("adult", "xxx", "18+", "porn")

    fun isAdultCategory(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        return keywords.any { it in lower }
    }

    fun <T> filterChannels(items: List<T>, hide: Boolean, group: (T) -> String?): List<T> =
        if (!hide) items else items.filterNot { isAdultCategory(group(it)) }
}
