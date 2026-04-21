package nl.vanvrouwerff.iptv.data.repo

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import nl.vanvrouwerff.iptv.IptvApp

/**
 * Nightly catalogue refresh, kicked off by WorkManager at the user-configured hour.
 * Reuses the same [PlaylistRefreshUseCase] as the manual refresh — everything below
 * is just scheduling glue.
 *
 * Failure policy:
 *  - No source configured → [Result.success]. Nothing to do and a retry won't help;
 *    the periodic schedule will try again tomorrow.
 *  - Network/parse error → [Result.retry]. WorkManager backs off and the periodic
 *    schedule still fires next cycle regardless.
 */
class PlaylistRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as IptvApp

        if (app.settings.sourceConfig.first() == null) {
            Log.i(TAG, "Skipping scheduled refresh — no source configured")
            return Result.success()
        }

        val result = app.refreshUseCase()
        return if (result.isSuccess) {
            Log.i(TAG, "Scheduled refresh finished")
            Result.success()
        } else {
            Log.w(TAG, "Scheduled refresh failed", result.exceptionOrNull())
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "playlist-refresh-nightly"
        private const val TAG = "PlaylistRefreshWorker"
    }
}
