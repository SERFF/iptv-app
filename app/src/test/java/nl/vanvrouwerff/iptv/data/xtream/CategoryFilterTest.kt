package nl.vanvrouwerff.iptv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryFilterTest {

    @Test
    fun `matches the common prefix styles`() {
        assertEquals("NL", CategoryFilter.prefixCode("┃NL┃ NEDERLAND HD"))
        assertEquals("UK", CategoryFilter.prefixCode("| UK | SPORTS"))
        assertEquals("EN", CategoryFilter.prefixCode("[EN] Comedy"))
        assertEquals("NL", CategoryFilter.prefixCode("NL: Kids"))
        assertEquals("USA", CategoryFilter.prefixCode("│USA│ Movies"))
    }

    @Test
    fun `filters on configured codes case-insensitively`() {
        val filter = CategoryFilter.parse("nl, uk")

        assertTrue(filter.accepts("┃NL┃ NEDERLAND HD"))
        assertTrue(filter.accepts("|UK| SKY"))
        assertFalse(filter.accepts("┃DE┃ SPORT"))
        assertFalse(filter.accepts("Movies"))
        assertFalse(filter.accepts(null))
    }

    @Test
    fun `empty filter accepts everything`() {
        val filter = CategoryFilter.parse("  ")

        assertFalse(filter.isEnabled)
        assertTrue(filter.accepts("Movies"))
        assertTrue(filter.accepts(null))
    }
}
