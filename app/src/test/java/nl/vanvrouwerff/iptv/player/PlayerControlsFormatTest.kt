package nl.vanvrouwerff.iptv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerControlsFormatTest {

    @Test
    fun seekStepAcceleratesWhileHeld() {
        assertEquals(10_000L, seekStepMs(0))
        assertEquals(30_000L, seekStepMs(5))
        assertEquals(60_000L, seekStepMs(20))
    }

    @Test
    fun formatsClockAndRemaining() {
        assertEquals("4:05", formatClock(245_000))
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("1u 12m", formatRemaining(72L * 60_000))
        assertEquals("9m", formatRemaining(9L * 60_000 + 30_000))
    }
}
