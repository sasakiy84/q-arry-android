package io.github.sasakiy84.qarry

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedNotificationsTest {
    @Test
    fun refreshNotifiesOnlyEnabledFeedsAndOpensTheirDetails() {
        val context = RuntimeEnvironment.getApplication()
        val preference = FeedNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val address = "https://test.example/feed"
        val feedTitle = "通知テスト"
        var key = "initial"
        val client = fixtureWebsiteClient(address) {
            """
            <rss><channel><title>$feedTitle</title>
                <item><guid>$key</guid><title>記事 $key</title></item>
            </channel></rss>
            """.trimIndent()
        }
        FeedStore(context).use { store ->
            val feed = store.subscribe(FeedDocument(address, feedTitle, null, emptyList()), null)
            val library = FeedLibrary(store, client, context = context)
            fun refresh(entry: String) {
                key = entry
                runBlocking { library.refresh(feed) }
                assertNotNull(store.feedArticles(feed, 10).articles.firstOrNull { it.title == "記事 $entry" })
            }

            store.setFeedNotifications(feed, FeedNotificationMode.On)
            refresh("no-permission")
            assertTrue(manager.activeNotifications.isEmpty())

            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            store.setFeedNotifications(feed, FeedNotificationMode.Default)
            refresh("default-off")
            assertTrue(manager.activeNotifications.isEmpty())

            store.setFeedNotifications(feed, FeedNotificationMode.On)
            refresh("override-on")
            val notification = manager.activeNotifications.single().notification
            assertEquals(feedTitle, notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals("記事 override-on", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertTrue(notification.contentIntent.isImmutable)
            val intent = shadowOf(notification.contentIntent).savedIntent
            assertEquals(feed, intent.notifiedFeedId())

            manager.cancelAll()
            refresh("override-on")
            assertTrue(manager.activeNotifications.isEmpty())

            preference.defaultEnabled = true
            store.setFeedNotifications(feed, FeedNotificationMode.Off)
            refresh("override-off")
            assertTrue(manager.activeNotifications.isEmpty())

            store.setFeedNotifications(feed, FeedNotificationMode.Default)
            refresh("default-on")
            assertEquals("記事 default-on", manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT))

            Robolectric.buildActivity(MainActivity::class.java, intent).setup().windowFocusChanged(true).use { controller ->
                val activity = controller.get()
                val screen = ComposeScreen(activity.window.decorView)
                screen.text("記事一覧")
                screen.text(feedTitle)
                activity.onBackPressedDispatcher.onBackPressed()
                screen.tap("設定")
                controller.newIntent(intent)
                screen.text("記事一覧")
                screen.text(feedTitle)
            }
        }
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationSettingsNavigationTest {
    @Test
    fun changesTheDefaultAndSavesASeparateFeedOverrideFromTheUi() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val feedTitle = "個別設定テスト"
        val preference = FeedNotifications(context)
        FeedStore(context).use { store ->
            store.subscribe(FeedDocument("https://test.example/feed", feedTitle, null, emptyList()), null)
            Robolectric.buildActivity(MainActivity::class.java).setup().windowFocusChanged(true).use { controller ->
                val activity = controller.get()
                val screen = ComposeScreen(activity.window.decorView)
                screen.tap("設定")
                screen.tapMatching("新着通知の既定値") {
                    "新着通知の既定値" in it.text() && SemanticsProperties.ToggleableState in it.config
                }
                assertTrue(preference.defaultEnabled)
                screen.tap("フィード")
                screen.tapContaining(feedTitle)
                screen.tapDescription("フィードのその他の操作")
                screen.dialogWith("新着通知を設定").tap("新着通知を設定")
                val settings = screen.dialogWith("既定に従う（オン）")
                settings.tap("オフ")
                settings.tap("保存")
                screen.find("通知設定を保存済み") { store.subscriptions().single().notifications == FeedNotificationMode.Off }
                assertTrue(preference.defaultEnabled)
            }
        }
    }
}
