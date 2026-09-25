package nl.vanvrouwerff.iptv.player

import kotlin.math.abs

/** Picks the display mode whose refresh rate suits a stream's frame rate (auto frame rate). */
object FrameRateMatcher {

    data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /** Refresh rates that show [fps] without judder, most preferred first. */
    fun suitableRefreshRates(fps: Float): List<Float> = when {
        near(fps, 23.976f) -> listOf(23.976f, 47.952f)
        near(fps, 24f) -> listOf(24f, 48f)
        near(fps, 25f) -> listOf(50f, 25f)
        near(fps, 29.97f) -> listOf(59.94f, 29.97f)
        near(fps, 30f) -> listOf(60f, 30f)
        near(fps, 50f) -> listOf(50f)
        near(fps, 59.94f) -> listOf(59.94f, 60f)
        near(fps, 60f) -> listOf(60f, 59.94f)
        else -> emptyList()
    }

    /**
     * Returns the mode to switch to, or null when the current mode already fits, the frame
     * rate is unknown, or the display offers no suitable mode at the current resolution.
     */
    fun pick(fps: Float, current: Mode, available: List<Mode>): Mode? {
        if (fps <= 0f) return null
        val rates = suitableRefreshRates(fps)
        if (rates.isEmpty()) return null
        if (rates.any { near(current.refreshRate, it) }) return null
        val sameResolution = available.filter { it.width == current.width && it.height == current.height }
        for (rate in rates) {
            sameResolution.firstOrNull { near(it.refreshRate, rate) }?.let { return it }
        }
        return null
    }

    private fun near(a: Float, b: Float): Boolean = abs(a - b) < 0.05f
}
