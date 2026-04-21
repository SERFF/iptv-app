package nl.vanvrouwerff.iptv.data.repo

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Owns the nightly [PlaylistRefreshWorker] schedule. All callers go through [apply] with
 * the current user preferences; the function handles the three possible state transitions
 * (enable, disable, hour changed) via WorkManager's unique-work API.
 */
object PlaylistRefreshScheduler {

    fun apply(context: Context, enabled: Boolean, hour: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(PlaylistRefreshWorker.UNIQUE_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<PlaylistRefreshWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(initialDelayMinutesUntil(hour), TimeUnit.MINUTES)
            .build()

        // UPDATE rather than KEEP: when the user changes the hour we want WorkManager to
        // re-anchor the schedule to the new time, not silently keep running at the old one.
        wm.enqueueUniquePeriodicWork(
            PlaylistRefreshWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Minutes from now until the next occurrence of `hour:00` in the device's local time
     * zone. If the target hour is later today we schedule for today; otherwise tomorrow.
     */
    private fun initialDelayMinutesUntil(hour: Int): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val diffMs = target.timeInMillis - now.timeInMillis
        return TimeUnit.MILLISECONDS.toMinutes(diffMs)
    }
}
