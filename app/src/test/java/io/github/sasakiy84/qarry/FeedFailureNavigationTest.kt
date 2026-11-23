package io.github.sasakiy84.qarry

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedFailureNavigationTest {
    @Test
    fun keepsTheFeedDetailUsableDuringAnOutageAndClearsTheErrorAfterRetry() {
        val address = "https://publisher.example/feed"
        val title = "通信テスト用フィード"
        val offline = AtomicBoolean(true)
        val recoveredFeed = """
            <rss><channel><title>$title</title>
            <item><guid>new</guid><title>復旧後の記事</title></item></channel></rss>
        """.trimIndent()
        val client = FeedClient { requested ->
            if (offline.get()) throw UnknownHostException("publisher.example")
            object : HttpURLConnection(URI(requested).toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = HTTP_OK
                override fun getContentType() = "application/rss+xml; charset=UTF-8"
                override fun getInputStream() = ByteArrayInputStream(recoveredFeed.toByteArray())
            }
        }
        FeedStore(RuntimeEnvironment.getApplication()).use { store ->
            val feedId = store.subscribe(
                FeedDocument(address, title, null, listOf(FeedArticle("old", "保存済みの記事", null, null, null))),
                null,
            )
            val emptyFeedId = store.subscribe(
                FeedDocument("https://publisher.example/empty", "記事のないフィード", null, emptyList()),
                null,
            )
            val library = FeedLibrary(store, client)
            runBlocking { library.refresh() }
            val connectionError = "接続先を見つけられませんでした。"
            Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true).use { controller ->
                val activity = controller.get()
                activity.setContent { MaterialTheme { QarryScreen(library) {} } }
                val screen = ComposeScreen(activity.window.decorView)
                screen.tap("フィード")
                screen.tapContaining("記事のないフィード")
                screen.text(connectionError)
                screen.text("取得済みの記事はありません")
                activity.onBackPressedDispatcher.onBackPressed()

                screen.tapContaining(title)
                screen.text(connectionError)
                screen.find("保存済みの記事") { "保存済みの記事" in it.text() }
                screen.text("キューを設定")

                offline.set(false)
                screen.tapDescription("今すぐ更新")
                screen.find("復旧後の記事") { "復旧後の記事" in it.text() }
                val feeds = store.subscriptions().associateBy { it.id }
                assertNull(feeds.getValue(feedId).error)
                assertEquals(connectionError, feeds.getValue(emptyFeedId).error)
            }
        }
    }

    @Test
    fun fallsBackToFeedContentWhenTheArticlePageConnectionIsDenied() {
        FeedStore(RuntimeEnvironment.getApplication()).use { store ->
            val library = FeedLibrary(store, FeedClient { throw SecurityException("connection denied") })
            assertNull(runBlocking { library.articleMetadata("https://publisher.example/article") })
        }
    }
}
