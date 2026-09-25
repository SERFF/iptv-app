package nl.vanvrouwerff.iptv.data

/** Strips provider noise ("|NL| ", "NL: ", trailing "|") from channel and category names for display. */
object DisplayNames {

    private const val BARS = "|┃│｜ǀ"
    private val bracketed = Regex("""^\s*[$BARS\[(]\s*[A-Za-z]{2,3}\s*[$BARS\])]\s*""")
    private val colon = Regex("""^\s*[A-Z]{2,3}\s*:\s*""")
    private val dashOrPipe = Regex("""^\s*[A-Z]{2}\s*[$BARS\-–]\s+""")
    private val edgeSeparators = Regex("""^[\s$BARS\-–:•·]+|[\s$BARS\-–:•·]+$""")

    fun clean(raw: String): String {
        var s = raw
        s = bracketed.replaceFirst(s, "")
        s = colon.replaceFirst(s, "")
        s = dashOrPipe.replaceFirst(s, "")
        s = edgeSeparators.replace(s, "")
        s = s.replace(Regex("""\s{2,}"""), " ")
        return s.ifBlank { raw.trim() }
    }
}
