package io.github.sasakiy84.qarry

import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryExportNavigationTest {
    @Test
    fun savesAnOpmlFromSettingsAndAJsonFileFromRatingHistory() {
        val context = RuntimeEnvironment.getApplication()
        val feed = "https://example.org/feed"
        FeedStore(context).use { store ->
            store.queueName(null, "読書")
            val queue = store.load(null, false, 1).queues.single()
            val feedId = store.subscribe(
                FeedDocument(feed, "読書の記録", null, listOf(FeedArticle("entry", "新しい本", null, null, null))),
                queue.id,
            )
            store.dismissArticle(store.feedArticles(feedId, 1).articles.single().id, ArticleRating.Good)
            Robolectric.buildActivity(MainActivity::class.java).setup().windowFocusChanged(true).use { controller ->
                val activity = controller.get()
                val screen = ComposeScreen(activity.window.decorView)
                fun saveAs(mime: String, filename: String): File {
                    val activityShadow = shadowOf(activity)
                    val request = checkNotNull(activityShadow.nextStartedActivityForResult)
                    assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
                    assertEquals(mime, request.intent.type)
                    assertEquals(filename, request.intent.getStringExtra(Intent.EXTRA_TITLE))
                    val file = File(activity.cacheDir, filename)
                    activity.activityResultRegistry.dispatchResult(
                        request.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)),
                    )
                    return file
                }

                screen.tap("設定")
                screen.tap("エクスポート")
                val opml = saveAs("text/x-opml", "q-arry-feeds.opml")
                screen.text("1件のフィードをエクスポートしました。")
                val settings = opml.inputStream().use(::readOpml)
                assertEquals(listOf("読書"), settings.queues)
                assertEquals(setOf("読書"), settings.feeds.single().queueFilters.keys)

                screen.tap("評価履歴")
                screen.tap("エクスポート（JSON）")
                val json = saveAs("application/json", "q-arry-ratings.json")
                screen.find("評価履歴の保存完了") { it.text() == "エクスポート（JSON）" && json.length() > 0 }
                val rating = JSONArray(json.readText()).getJSONObject(0)
                assertEquals(feed, rating.getString("feedUrl"))
                assertEquals("Good", rating.getString("rating"))
            }
        }
    }
}
