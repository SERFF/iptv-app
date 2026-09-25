package nl.vanvrouwerff.iptv.player

/**
 * Estimates a stream's frame rate from presentation timestamps when the container does not
 * declare one (common for broadcast MPEG-TS). Reports once per [reset], after [SAMPLES] frames.
 */
class FrameRateProbe {

    private val times = LongArray(SAMPLES)
    private var count = 0
    private var reported = false

    @Synchronized
    fun reset() {
        count = 0
        reported = false
    }

    @Synchronized
    fun hasReported(): Boolean = reported

    @Synchronized
    fun markReported() {
        reported = true
    }

    /** Adds a frame; returns the estimated fps exactly once, when enough frames were seen. */
    @Synchronized
    fun add(presentationTimeUs: Long): Float? {
        if (reported) return null
        times[count++] = presentationTimeUs
        if (count < SAMPLES) return null
        reported = true
        return estimate(times.copyOf(count))
    }

    companion object {
        const val SAMPLES = 40

        private val STANDARD_RATES = floatArrayOf(24f, 25f, 29.97f, 50f, 59.94f)

        /** Rounds a measured rate to the nearest broadcast/film rate, or null when none is within 8%. */
        fun snap(measuredFps: Float): Float? {
            if (measuredFps <= 0f) return null
            val nearest = STANDARD_RATES.minByOrNull { kotlin.math.abs(it - measuredFps) } ?: return null
            return nearest.takeIf { kotlin.math.abs(it - measuredFps) / it <= 0.08f }
        }

        /** Median frame interval → frames per second; null when the timestamps make no sense. */
        fun estimate(presentationTimesUs: LongArray): Float? {
            val sorted = presentationTimesUs.sorted()
            val deltas = sorted.zipWithNext { a, b -> b - a }.filter { it in 1_000L..100_000L }.sorted()
            if (deltas.size < SAMPLES / 2) return null
            val median = deltas[deltas.size / 2]
            return 1_000_000f / median
        }
    }
}
