package nl.vanvrouwerff.iptv.data.xtream

/**
 * Matches Xtream category names against a user-configured list of country/language codes.
 * Providers prefix categories like "┃NL┃ NEDERLAND HD", "| UK | SPORTS", "[EN] Comedy" or
 * "NL: Kids". An empty code list keeps everything.
 */
class CategoryFilter(codes: Collection<String>) {

    private val normalisedCodes: Set<String> = codes
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    val isEnabled: Boolean get() = normalisedCodes.isNotEmpty()

    fun accepts(categoryName: String?): Boolean {
        if (!isEnabled) return true
        val code = prefixCode(categoryName ?: return false) ?: return false
        return code in normalisedCodes
    }

    companion object {
        private val Delimited = Regex("""^\s*[|│┃\[(]+\s*([A-Za-z]{2,4})\s*[|│┃\])]+""")
        private val Plain = Regex("""^\s*([A-Za-z]{2,4})\s*[:\-|]""")

        fun parse(raw: String): CategoryFilter =
            CategoryFilter(raw.split(',', ';', ' ').filter { it.isNotBlank() })

        fun prefixCode(categoryName: String): String? =
            (Delimited.find(categoryName) ?: Plain.find(categoryName))
                ?.groupValues?.get(1)?.uppercase()
    }
}
