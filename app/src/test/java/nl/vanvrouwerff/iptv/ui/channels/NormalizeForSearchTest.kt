package nl.vanvrouwerff.iptv.ui.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizeForSearchTest {

    @Test
    fun stripsAccentsAndCase() {
        assertEquals("pokemon", normalizeForSearch("Pokémon"))
        assertEquals("creme brulee", normalizeForSearch("  Crème Brûlée "))
    }

    @Test
    fun accentlessQueryMatchesAccentedTitle() {
        assertTrue(normalizeForSearch("Pokémon: De Film").contains(normalizeForSearch("pokemon")))
    }
}
