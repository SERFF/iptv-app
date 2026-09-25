package nl.vanvrouwerff.iptv.data.repo

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import nl.vanvrouwerff.iptv.IptvApp
import java.util.concurrent.TimeUnit

/** Refreshes only the EPG every six hours, independent of the (nightly) catalogue refresh. */
class EpgRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as IptvApp
        return if (app.refreshUseCase.refreshEpg().isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "epg-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
