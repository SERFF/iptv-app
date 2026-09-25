package nl.vanvrouwerff.iptv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNamesTest {

    private fun check(raw: String, expected: String) = assertEquals(expected, DisplayNames.clean(raw))

    @Test fun pipeWrappedPrefix() = check("|NL| HBO MAX SPORT 1 |", "HBO MAX SPORT 1")
    @Test fun pipeWrappedWithSpaces() = check("| NL | NPO 1 HD", "NPO 1 HD")
    @Test fun squareBrackets() = check("[NL] RTL 4", "RTL 4")
    @Test fun parentheses() = check("(UK) BBC One", "BBC One")
    @Test fun colonPrefix() = check("NL: Ziggo Sport", "Ziggo Sport")
    @Test fun threeLetterColonPrefix() = check("NLD: Veronica", "Veronica")
    @Test fun dashPrefix() = check("NL - SBS 6", "SBS 6")
    @Test fun pipePrefix() = check("NL | Viaplay", "Viaplay")
    @Test fun categoryKeepsInnerSeparator() = check("|NL| NEDERLAND HD | TERUGKIJKEN", "NEDERLAND HD | TERUGKIJKEN")
    @Test fun leavesNormalNames() = check("NPO 1", "NPO 1")
    @Test fun doesNotEatThreeLetterBrandBeforeDash() = check("ZDF - Info", "ZDF - Info")
    @Test fun doesNotEatBrandWithoutSeparator() = check("BBC One", "BBC One")
    @Test fun heavyBoxBars() = check("┃NL┃ NPO 1 HD", "NPO 1 HD")
    @Test fun heavyBoxBarsKeepSuffix() = check("┃NL┃ NPO 1 HD  ⏺ʳᵉᶜ", "NPO 1 HD ⏺ʳᵉᶜ")
    @Test fun neverReturnsBlank() = check("|NL|", "|NL|")
}
