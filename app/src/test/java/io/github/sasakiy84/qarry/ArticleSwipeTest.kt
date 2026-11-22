package io.github.sasakiy84.qarry

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArticleSwipeTest {
    @Test
    fun articleCardsDistinguishTapsAndFullSwipes() {
        val context = RuntimeEnvironment.getApplication()
        FeedStore(context).use { store ->
            val feedId = store.subscribe(
                FeedDocument(
                    url = "https://example.org/feed", title = "Swipe QA", home = "https://example.org/",
                    articles = listOf(
                        FeedArticle("good", "Article to like", null, "Short description", 2000L),
                        FeedArticle("bad", "Article to dislike", null, "Short description", 1000L),
                    ),
                ),
                null,
            )
            store.queueName(null, "Research")
            store.queueName(null, "Daily")
            val queues = store.load(null, false, 10).queues
            store.setMembership(feedId, queues.map { it.id }.toSet())
            val snapshot = mutableStateOf(store.load(null, false, 10))
            val (goodArticle, badArticle) = snapshot.value.articles
            val presses = mutableListOf<Pair<Long, ArticlePress>>()
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                activity.setContent {
                    MaterialTheme {
                        ArticlesPage(
                            snapshot.value, queueId = null, unread = false,
                            markingRead = false, onQueue = {}, onUnread = {}, onMarkAllRead = {}, onAdd = {}, onMore = {},
                            onArticle = { article, press -> presses.add(article.id to press) },
                            onDismiss = { article, rating ->
                                store.dismissArticle(article.id, rating)
                                snapshot.value = store.load(null, false, 10)
                                true
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                val root = activity.window.decorView
                settle(root, 20)
                tap(root)
                assertEquals(listOf(goodArticle.id to ArticlePress.Tap), presses)
                presses.clear()

                drag(root, listOf(130f, 230f), stepMillis = 4)
                settle(root, 25)
                assertNull("A fast short flick must not rate the article", store.article(goodArticle.id)?.rating)
                assertEquals(listOf(goodArticle.id, badArticle.id), snapshot.value.articles.map { it.id })

                drag(root, listOf(62f, 298f, 125f))
                settle(root, 25)
                assertNull("The final position must still be beyond the threshold", store.article(goodArticle.id)?.rating)

                drag(root, listOf(62f, 298f), finish = MotionEvent.ACTION_CANCEL)
                settle(root, 25)
                assertNull("A cancelled touch must not rate the article", store.article(goodArticle.id)?.rating)

                drag(root, listOf(62f, 298f))
                settle(root, 55)
                assertEquals(ArticleRating.Good, store.article(goodArticle.id)?.rating)
                assertEquals(listOf(badArticle.id), snapshot.value.articles.map { it.id })

                drag(root, listOf(260f, 155f), stepMillis = 4)
                settle(root, 25)
                assertNull(store.article(badArticle.id)?.rating)

                drag(root, listOf(298f, 62f))
                settle(root, 55)
                assertEquals(ArticleRating.Bad, store.article(badArticle.id)?.rating)
                assertTrue(snapshot.value.articles.isEmpty())
                queues.forEach { queue -> assertTrue(store.load(queue.id, false, 10).articles.isEmpty()) }
                assertTrue("A horizontal swipe must not open the article", presses.isEmpty())
            }
        }
    }

    private fun tap(root: View) {
        val start = SystemClock.uptimeMillis()
        dispatch(root, start, MotionEvent.ACTION_DOWN, 160f)
        settle(root, 2)
        dispatch(root, start, MotionEvent.ACTION_UP, 160f)
        settle(root, 2)
    }

    private fun drag(root: View, stops: List<Float>, finish: Int = MotionEvent.ACTION_UP, stepMillis: Long = 16L) {
        val downTime = SystemClock.uptimeMillis()
        dispatch(root, downTime, MotionEvent.ACTION_DOWN, stops.first())
        for ((from, to) in stops.zipWithNext()) {
            for (step in 1..12) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMillis))
                dispatch(root, downTime, MotionEvent.ACTION_MOVE, from + (to - from) * step / 12)
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMillis))
        dispatch(root, downTime, finish, stops.last())
    }

    private fun dispatch(root: View, downTime: Long, action: Int, x: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, 184f, 0)
        try {
            root.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun settle(root: View, frames: Int) {
        repeat(frames) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 360, 800)
        }
    }
}
