package nl.vanvrouwerff.iptv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultContentTest {

    @Test fun matchesKeywordsCaseInsensitive() {
        assertTrue(AdultContent.isAdultCategory("┃XXX┃ ADULT CHANNELS"))
        assertTrue(AdultContent.isAdultCategory("Movies 18+"))
        assertTrue(AdultContent.isAdultCategory("Porn HD"))
        assertTrue(AdultContent.isAdultCategory("adult"))
    }

    @Test fun leavesOtherCategories() {
        assertFalse(AdultContent.isAdultCategory("NEDERLAND HD"))
        assertFalse(AdultContent.isAdultCategory("OD ZONE+ KIDS"))
        assertFalse(AdultContent.isAdultCategory(null))
    }

    @Test fun filtersOnlyWhenHidden() {
        val items = listOf("NL" to "Nederland", "X" to "XXX")
        assertEquals(2, AdultContent.filterChannels(items, hide = false) { it.second }.size)
        assertEquals(listOf("NL" to "Nederland"), AdultContent.filterChannels(items, hide = true) { it.second })
    }
}
