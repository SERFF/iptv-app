package nl.vanvrouwerff.iptv.player

import nl.vanvrouwerff.iptv.player.FrameRateMatcher.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMatcherTest {

    private val hd60 = Mode(1, 1920, 1080, 60f)
    private val hd50 = Mode(2, 1920, 1080, 50f)
    private val hd24 = Mode(3, 1920, 1080, 24f)
    private val hd5994 = Mode(4, 1920, 1080, 59.94f)
    private val uhd50 = Mode(5, 3840, 2160, 50f)
    private val all = listOf(hd60, hd50, hd24, hd5994, uhd50)

    @Test fun pal25GoesTo50() = assertEquals(hd50, FrameRateMatcher.pick(25f, hd60, all))
    @Test fun pal50GoesTo50() = assertEquals(hd50, FrameRateMatcher.pick(50f, hd60, all))
    @Test fun film24GoesTo24() = assertEquals(hd24, FrameRateMatcher.pick(24f, hd60, all))
    @Test fun ntsc2997PrefersExact5994() = assertEquals(hd5994, FrameRateMatcher.pick(29.97f, hd50, all))
    @Test fun fps60GoesTo60() = assertEquals(hd60, FrameRateMatcher.pick(60f, hd50, all))
    @Test fun noSwitchWhenCurrentFits() = assertNull(FrameRateMatcher.pick(25f, hd50, all))
    @Test fun noSwitchFor5994On60() = assertNull(FrameRateMatcher.pick(59.94f, hd60, all))
    @Test fun keepsResolution() = assertNull(FrameRateMatcher.pick(25f, Mode(9, 1280, 720, 60f), all))
    @Test fun unknownFrameRateDoesNothing() = assertNull(FrameRateMatcher.pick(-1f, hd60, all))
    @Test fun oddFrameRateDoesNothing() = assertNull(FrameRateMatcher.pick(15f, hd60, all))
    @Test fun film24FallsBackToNothingWithoutMode() = assertNull(FrameRateMatcher.pick(24f, hd60, listOf(hd60, hd50)))
}
