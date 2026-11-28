package io.github.sasakiy84.qarry

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI

internal data class BackgroundUpdateSettings(
    val feedWifiOnly: Boolean = false,
    val archiveUnmeteredOnly: Boolean = true,
    val feedOnOpenOnly: Boolean = false,
    val archiveChargingOnly: Boolean = true,
) {
    fun networkRequest(jobId: Int): NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .apply {
            when (jobId) {
                FEED_REFRESH_JOB -> if (feedWifiOnly) addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                FULL_TEXT_JOB -> if (archiveUnmeteredOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }.build()
}

internal class BackgroundUpdates(private val context: Context) {
    private val preferences = context.getSharedPreferences("background_updates", Context.MODE_PRIVATE)
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    val settings: BackgroundUpdateSettings
        get() = BackgroundUpdateSettings(
            feedWifiOnly = preferences.getBoolean("feed_wifi_only", false),
            archiveUnmeteredOnly = preferences.getBoolean("archive_unmetered_only", true),
            feedOnOpenOnly = preferences.getBoolean("feed_on_open_only", false),
            archiveChargingOnly = preferences.getBoolean("archive_charging_only", true),
        )

    fun update(settings: BackgroundUpdateSettings) {
        preferences.edit {
            putBoolean("feed_wifi_only", settings.feedWifiOnly)
            putBoolean("archive_unmetered_only", settings.archiveUnmeteredOnly)
            putBoolean("feed_on_open_only", settings.feedOnOpenOnly)
            putBoolean("archive_charging_only", settings.archiveChargingOnly)
        }
        schedule()
    }

    fun schedule() {
        val current = settings
        val service = ComponentName(context, FeedRefreshService::class.java)
        for (id in listOf(FEED_REFRESH_JOB, FULL_TEXT_JOB)) {
            if (id == FEED_REFRESH_JOB && current.feedOnOpenOnly) {
                scheduler.cancel(id)
                continue
            }
            val job = JobInfo.Builder(id, service)
                .setRequiredNetwork(current.networkRequest(id))
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(id == FULL_TEXT_JOB && current.archiveChargingOnly)
                .setPeriodic(if (id == FEED_REFRESH_JOB) 12 * HOUR_MS else HOUR_MS)
                .setPersisted(true)
                .build()
            if (scheduler.getPendingJob(id) == job) continue
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.w(BACKGROUND_LOG, "Could not schedule background job $id")
            }
        }
    }

    fun claimOpenRefresh(now: Long = System.currentTimeMillis()): FeedClient? {
        if (!settings.feedOnOpenOnly) return null
        val lastAttempt = preferences.getLong("last_open_refresh_attempt", 0L)
        if (lastAttempt > 0 && now - lastAttempt in 0L until OPEN_REFRESH_GAP_MS) return null
        val network = connectivity.activeNetwork ?: return null
        val client = clientFor(FEED_REFRESH_JOB, network, fromOpen = true) ?: return null
        preferences.edit { putLong("last_open_refresh_attempt", now) }
        return client
    }

    fun clientFor(jobId: Int, network: Network, fromOpen: Boolean = false): FeedClient? {
        fun allowed(): Boolean {
            val current = settings
            if (jobId == FEED_REFRESH_JOB && current.feedOnOpenOnly != fromOpen) return false
            return current.networkRequest(jobId).canBeSatisfiedBy(connectivity.getNetworkCapabilities(network))
        }

        if (!allowed()) return null
        return FeedClient { url ->
            // The default network can be cellular even when the job was scheduled on Wi-Fi.
            if (!allowed()) throw CancellationException("Automatic update no longer meets the requested conditions")
            network.openConnection(URI(url).toURL()) as HttpURLConnection
        }
    }
}

class FeedRefreshService : JobService() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val runningJobs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.jobId
        if (id != FEED_REFRESH_JOB && id != FULL_TEXT_JOB) return false
        val network = params.network ?: return false
        val app = application as QarryApplication
        val client = app.backgroundUpdates.clientFor(id, network) ?: return false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                when (id) {
                    FEED_REFRESH_JOB -> app.library.refresh(requestClient = client)
                    FULL_TEXT_JOB -> app.library.archive.fetchPending(requestClient = client, completeLexicalIndex = false)
                }
            } catch (_: SQLiteException) {
                Log.w(BACKGROUND_LOG, "Background job $id could not access the database")
                retry = true
            } finally {
                if (runningJobs[id] === coroutineContext[Job]) {
                    runningJobs.remove(id)
                    jobFinished(params, retry)
                }
            }
        }
        runningJobs.put(id, job)?.cancel()
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        runningJobs.clear()
        scope.cancel()
        super.onDestroy()
    }
}

internal const val FEED_REFRESH_JOB = 241
internal const val FULL_TEXT_JOB = 242
private const val HOUR_MS = 60 * 60 * 1_000L
private const val OPEN_REFRESH_GAP_MS = HOUR_MS / 2
private const val BACKGROUND_LOG = "QarryBackground"
