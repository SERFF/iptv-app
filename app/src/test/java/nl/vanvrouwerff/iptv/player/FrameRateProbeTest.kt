package nl.vanvrouwerff.iptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateProbeTest {

    private fun frames(intervalUs: Long, n: Int = FrameRateProbe.SAMPLES) = LongArray(n) { 10_000_000L + it * intervalUs }

    @Test fun pal25() = assertEquals(25f, FrameRateProbe.estimate(frames(40_000))!!, 0.01f)
    @Test fun hd50() = assertEquals(50f, FrameRateProbe.estimate(frames(20_000))!!, 0.01f)
    @Test fun ntsc2997() = assertEquals(29.97f, FrameRateProbe.estimate(frames(33_367))!!, 0.01f)

    @Test fun outOfOrderTimestampsStillWork() {
        val t = frames(40_000)
        val shuffled = LongArray(t.size) { i -> if (i % 2 == 0 && i + 1 < t.size) t[i + 1] else if (i % 2 == 1) t[i - 1] else t[i] }
        assertEquals(25f, FrameRateProbe.estimate(shuffled)!!, 0.01f)
    }

    @Test fun reportsOnceAfterEnoughFrames() {
        val probe = FrameRateProbe()
        val t = frames(40_000)
        val results = t.map { probe.add(it) }
        assertEquals(1, results.count { it != null })
        assertNull(probe.add(99_000_000L))
    }

    @Test fun snapsMeasuredPal() = assertEquals(25f, FrameRateProbe.snap(24.6f)!!, 0.001f)
    @Test fun snapsMeasured50() = assertEquals(50f, FrameRateProbe.snap(49.1f)!!, 0.001f)
    @Test fun snapsMeasured60() = assertEquals(59.94f, FrameRateProbe.snap(60.3f)!!, 0.001f)
    @Test fun snapsMeasuredFilm() = assertEquals(24f, FrameRateProbe.snap(23.9f)!!, 0.001f)
    @Test fun rejectsOddRate() = assertNull(FrameRateProbe.snap(40f))
    @Test fun garbageGivesNull() = assertNull(FrameRateProbe.estimate(LongArray(FrameRateProbe.SAMPLES) { 5L }))
}
