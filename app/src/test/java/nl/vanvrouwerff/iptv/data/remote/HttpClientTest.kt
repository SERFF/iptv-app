package nl.vanvrouwerff.iptv.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpClientTest {

    @Test
    fun `redacts credentials in query strings and stream paths`() {
        assertEquals(
            "--> GET http://h/player_api.php?username=***&password=***&action=get_live_streams",
            HttpClient.redact("--> GET http://h/player_api.php?username=jan&password=geheim&action=get_live_streams"),
        )
        assertEquals(
            "--> GET http://h/live/***/***/1.ts",
            HttpClient.redact("--> GET http://h/live/jan/geheim/1.ts"),
        )
    }
}
