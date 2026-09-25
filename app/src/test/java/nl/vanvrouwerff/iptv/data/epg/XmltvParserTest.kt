package nl.vanvrouwerff.iptv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmltvParserTest {

    @Test
    fun `parses utc timestamps`() {
        assertEquals(1776709800000L, XmltvParser.parseXmltvTime("20260420183000"))
    }

    @Test
    fun `applies offsets with and without a space`() {
        val utc = XmltvParser.parseXmltvTime("20260420163000")
        assertEquals(utc, XmltvParser.parseXmltvTime("20260420183000 +0200"))
        assertEquals(utc, XmltvParser.parseXmltvTime("20260420183000+0200"))
    }

    @Test
    fun `accepts timestamps without seconds`() {
        assertEquals(
            XmltvParser.parseXmltvTime("20260420183000"),
            XmltvParser.parseXmltvTime("202604201830"),
        )
    }

    @Test
    fun `rejects garbage`() {
        assertNull(XmltvParser.parseXmltvTime("2026"))
    }
}
