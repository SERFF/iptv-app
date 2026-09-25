package nl.vanvrouwerff.iptv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamUrlsTest {

    @Test
    fun `builds plain stream urls unchanged`() {
        assertEquals(
            "http://host.tv:8080/live/user/pass/123.ts",
            XtreamUrls.stream("http://host.tv:8080/", "live", "user", "pass", "123.ts"),
        )
    }

    @Test
    fun `encodes credentials with reserved characters`() {
        assertEquals(
            "http://host.tv/movie/us%20er/p%23ss%2Fw/9.mkv",
            XtreamUrls.stream("http://host.tv", "movie", "us er", "p#ss/w", "9.mkv"),
        )
    }
}
