package nl.vanvrouwerff.iptv.data.tmdb

import nl.vanvrouwerff.iptv.data.Channel

/**
 * Shared fuzzy title-matcher core. TMDB returns clean titles ("Dune: Part Two"); Xtream
 * provider titles are routinely cluttered with year suffixes, language tags, and release
 * tags ("4K Film: Dune Part Two 2024"). Normalise both sides before comparing.
 *
 * The match is intentionally conservative — we only surface titles that ALSO exist in the
 * user's catalogue. A rail full of "you can't watch this" items would be frustrating.
 *
 * SeriesMatcher and MovieMatcher are thin type-specific wrappers around this core; they
 * only differ in how they extract candidate titles from their TMDB DTO (name/originalName
 * vs title/originalTitle).
 */
object TmdbCatalogueMatcher {

    // Compiled once. normalize() runs ~32k times per match() call on a populated tab —
    // creating fresh Regex state machines per call would dominate the work.
    // CountryFlag strips the leading language/country tag Xtream providers prepend to
    // every row, e.g. "┃EN┃ The Caller", "| USA┃ Sherlock & Daughter". The delimiters
    // vary across providers — ASCII pipe, box-drawing ┃ (U+2503), and light │ (U+2502)
    // are all in the wild; the 2–4-letter window covers EN/NL/US/USA/FRA/DEU and friends.
    private val CountryFlag = Regex("^\\s*[|│┃]+\\s*[a-z]{2,4}\\s*[|│┃]+\\s*")
    private val QualityPrefix = Regex("^(4k|hd|fhd|uhd|sd)\\s*[:|-]\\s*")
    private val TypePrefix = Regex("^(series|serie|tv|film|movie|vod)\\s*[:|-]\\s*")
    private val BracketedYear = Regex("[\\[(]\\s*\\d{4}\\s*[\\])]")
    private val BareYear = Regex("\\b(19|20)\\d{2}\\b")
    private val NonAlnum = Regex("[^a-z0-9 ]")
    private val Whitespace = Regex("\\s+")

    /** Strip common Xtream prefixes/suffixes, lowercase, collapse whitespace. */
    fun normalize(raw: String): String {
        var s = raw.lowercase()
        s = s.replace(CountryFlag, "")
        s = s.replace(QualityPrefix, "")
        s = s.replace(TypePrefix, "")
        s = s.replace(BracketedYear, " ")
        s = s.replace(BareYear, " ")
        s = s.replace(NonAlnum, " ")
        s = s.replace(Whitespace, " ").trim()
        return s
    }

    /**
     * Returns the user's catalogue channels whose normalised title matches a TMDB popular
     * item, preserving TMDB's popularity order (so the rail reads "most popular first").
     * De-dups if the same Xtream channel matches multiple TMDB titles.
     *
     * [titles] extracts the candidate titles to try per TMDB item (typically the
     * localised title plus the original-language title).
     */
    fun <T> match(
        tmdb: List<T>,
        titles: (T) -> List<String>,
        catalogue: List<Channel>,
    ): List<Channel> {
        if (tmdb.isEmpty() || catalogue.isEmpty()) return emptyList()

        val byNormalised: HashMap<String, Channel> = HashMap(catalogue.size)
        catalogue.forEach { ch ->
            val key = normalize(ch.name)
            if (key.isNotEmpty()) byNormalised.putIfAbsent(key, ch)
        }

        val seen = HashSet<String>()
        return tmdb.mapNotNull { item ->
            titles(item)
                .firstNotNullOfOrNull { title -> byNormalised[normalize(title)] }
                ?.takeIf { seen.add(it.id) }
        }
    }
}
