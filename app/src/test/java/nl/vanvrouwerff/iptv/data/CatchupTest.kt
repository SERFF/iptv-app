package nl.vanvrouwerff.iptv.data

import nl.vanvrouwerff.iptv.data.catchup.Catchup
import nl.vanvrouwerff.iptv.data.xtream.XtreamUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CatchupTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private val start = ZonedDateTime.of(2026, 9, 25, 21, 20, 0, 0, amsterdam).toInstant().toEpochMilli()
    private val hour = 3_600_000L
    private val channel = Channel("xt-live:1359", "NPO 1", null, null, "u", "npo1.nl", ContentType.TV, archiveDays = 3)

    @Test fun timeshiftUrlUsesServerLocalTime() {
        val url = XtreamUrls.timeshift("http://host:80", "user", "pass", "1359", start, 55, amsterdam)
        assertEquals("http://host/timeshift/user/pass/55/2026-09-25:21-20/1359.m3u8", url)
    }

    @Test fun timeshiftUrlConvertsFromUtcServer() {
        val url = XtreamUrls.timeshift("http://host", "user", "pass", "1359", start, 55, ZoneId.of("UTC"))
        assertEquals("http://host/timeshift/user/pass/55/2026-09-25:19-20/1359.m3u8", url)
    }

    @Test fun availableWithinArchive() =
        assertTrue(Catchup.isAvailable(channel, start, start + hour, start + 5 * hour))

    @Test fun notAvailableBeyondArchive() =
        assertFalse(Catchup.isAvailable(channel, start, start + hour, start + 4 * 24 * hour))

    @Test fun notAvailableWithoutArchive() =
        assertFalse(Catchup.isAvailable(channel.copy(archiveDays = 0), start, start + hour, start + 5 * hour))

    @Test fun notAvailableForFutureProgramme() =
        assertFalse(Catchup.isAvailable(channel, start, start + hour, start - hour))
}
