package nl.vanvrouwerff.iptv.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSetupTest {

    @Test
    fun countsMarkersAcrossBufferBoundaries() {
        val json = (1..5000).joinToString(",", "[", "]") { """{"stream_id":$it,"name":"x"}""" }
        assertEquals(5000, SourceTester.countOccurrences(json.byteInputStream(), "\"stream_id\""))
    }

    @Test
    fun countsNothingInEmptyList() {
        assertEquals(0, SourceTester.countOccurrences("[]".byteInputStream(), "\"series_id\""))
    }

    @Test
    fun parsesUrlEncodedForm() {
        val form = PhoneSetupServer.parseForm("type=xtream&host=http%3A%2F%2Fprov.tv%3A8080&username=jan&password=a%26b+c")
        assertEquals("xtream", form["type"])
        assertEquals("http://prov.tv:8080", form["host"])
        assertEquals("jan", form["username"])
        assertEquals("a&b c", form["password"])
    }
}
