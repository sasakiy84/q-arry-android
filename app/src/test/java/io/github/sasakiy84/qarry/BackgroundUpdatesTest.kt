package io.github.sasakiy84.qarry

import android.app.job.JobScheduler
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackgroundUpdatesTest {
    @Test
    fun appliesEachRestrictionToItsOwnNetwork() {
        val app = RuntimeEnvironment.getApplication() as QarryApplication
        val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        val mobile = ShadowNetwork.newInstance(90)
        val wifi = ShadowNetwork.newInstance(91)
        val ethernet = ShadowNetwork.newInstance(92)
        connectivity.setNetworkCapabilities(mobile, internet(NetworkCapabilities.TRANSPORT_CELLULAR))
        connectivity.setNetworkCapabilities(wifi, internet(NetworkCapabilities.TRANSPORT_WIFI))
        connectivity.setNetworkCapabilities(ethernet, internet(NetworkCapabilities.TRANSPORT_ETHERNET, unmetered = true))
        val updates = app.backgroundUpdates

        assertNotNull(updates.clientFor(FEED_REFRESH_JOB, mobile))
        assertNull(updates.clientFor(FULL_TEXT_JOB, mobile))
        assertNull(updates.clientFor(FULL_TEXT_JOB, wifi))
        assertNotNull(updates.clientFor(FULL_TEXT_JOB, ethernet))

        updates.update(BackgroundUpdateSettings(feedWifiOnly = true, archiveUnmeteredOnly = false))
        assertNull(updates.clientFor(FEED_REFRESH_JOB, mobile))
        assertNull(updates.clientFor(FEED_REFRESH_JOB, ethernet))
        assertNotNull(updates.clientFor(FEED_REFRESH_JOB, wifi))
        assertNotNull(updates.clientFor(FULL_TEXT_JOB, mobile))
    }

    @Test
    fun checksTheAssignedNetworkAgainBeforeOpeningAnArticlePage() {
        val app = RuntimeEnvironment.getApplication() as QarryApplication
        val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        val network = ShadowNetwork.newInstance(94)
        connectivity.setNetworkCapabilities(network, internet(NetworkCapabilities.TRANSPORT_WIFI, unmetered = true))
        val client = checkNotNull(app.backgroundUpdates.clientFor(FULL_TEXT_JOB, network))

        connectivity.setNetworkCapabilities(network, internet(NetworkCapabilities.TRANSPORT_WIFI))

        assertThrows(CancellationException::class.java) {
            client.articleText("https://publisher.example/article")
        }
    }

    private fun internet(transport: Int, unmetered: Boolean = false): NetworkCapabilities {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        val shadow = shadowOf(capabilities)
        shadow.addTransportType(transport)
        NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build().capabilities.forEach(shadow::addCapability)
        if (unmetered) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return capabilities
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundUpdateSettingsNavigationTest {
    @Test
    fun updatesAndPersistsTheTwoPeriodicJobsFromSettings() {
        val app = RuntimeEnvironment.getApplication() as QarryApplication
        val scheduler = app.getSystemService(JobScheduler::class.java)
        val wifiTransport = NetworkCapabilities.TRANSPORT_WIFI
        val unmetered = NetworkCapabilities.NET_CAPABILITY_NOT_METERED
        fun request(jobId: Int) = checkNotNull(scheduler.getPendingJob(jobId)).let { job ->
            assertTrue(job.isPeriodic)
            assertTrue(job.isPersisted)
            assertTrue(job.isRequireBatteryNotLow)
            checkNotNull(job.requiredNetwork)
        }

        assertFalse(request(FEED_REFRESH_JOB).hasTransport(wifiTransport))
        assertFalse(request(FEED_REFRESH_JOB).hasCapability(unmetered))
        assertTrue(request(FULL_TEXT_JOB).hasCapability(unmetered))

        Robolectric.buildActivity(MainActivity::class.java).setup().windowFocusChanged(true).use { controller ->
            val screen = ComposeScreen(controller.get().window.decorView)
            val wifiLabel = "フィードの自動更新は Wi-Fi のみ"
            val archiveLabel = "自動全文取得は非従量制回線のみ"
            fun state(label: String) = screen.find(label) {
                label in it.text() && SemanticsProperties.ToggleableState in it.config
            }.config[SemanticsProperties.ToggleableState]

            screen.tap("設定")
            assertEquals(ToggleableState.Off, state(wifiLabel))
            assertEquals(ToggleableState.On, state(archiveLabel))

            screen.tap(wifiLabel)
            assertTrue(request(FEED_REFRESH_JOB).hasTransport(wifiTransport))
            assertTrue(request(FULL_TEXT_JOB).hasCapability(unmetered))

            screen.tap(archiveLabel)
            assertFalse(request(FULL_TEXT_JOB).hasCapability(unmetered))
            assertTrue(request(FEED_REFRESH_JOB).hasTransport(wifiTransport))
            assertEquals(BackgroundUpdateSettings(true, false), BackgroundUpdates(app).settings)
        }
        assertEquals(setOf(FEED_REFRESH_JOB, FULL_TEXT_JOB), scheduler.allPendingJobs.map { it.id }.toSet())
    }
}
