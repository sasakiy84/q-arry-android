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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
    fun diagonalDragsScrollAndHorizontalDragsRemoveOrRateAtTheReleaseDistance() {
        val context = RuntimeEnvironment.getApplication()
        FeedStore(context).use { store ->
            val feedId = store.subscribe(
                FeedDocument(
                    url = "https://example.org/feed", title = "Swipe QA", home = "https://example.org/",
                    articles = listOf(
                        FeedArticle("remove", "Article to remove", null, "Short description", 4000L),
                        FeedArticle("bad", "Article to dislike", null, "Short description", 3000L),
                        FeedArticle("good", "Article to like", null, "Short description", 2000L),
                    ) + List(12) { index -> FeedArticle("more-$index", "More news $index", null, null, index.toLong()) },
                ),
                null,
            )
            store.queueName(null, "Research")
            store.queueName(null, "Daily")
            val queues = store.load(null, false, 10).queues
            store.setMembership(feedId, queues.map { it.id }.toSet())
            val snapshot = mutableStateOf(store.load(null, false, 20))
            val (removeArticle, badArticle, goodArticle) = snapshot.value.articles
            val originalIds = snapshot.value.articles.map { it.id }
            val presses = mutableListOf<Pair<Long, ArticlePress>>()
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                activity.setContent {
                    MaterialTheme {
                        ArticlesPage(
                            snapshot.value, queueId = null, unread = false,
                            markingRead = false, onQueueFilter = { _, _ -> }, onUnread = {}, onMarkAllRead = {}, onAdd = {}, onMore = {},
                            onArticle = { article, press -> presses.add(article.id to press) },
                            onDismiss = { article, rating ->
                                store.dismissArticle(article.id, rating)
                                snapshot.value = store.load(null, false, 20)
                                true
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                val root = activity.window.decorView
                val screen = ComposeScreen(root)
                fun card(article: Article) = screen.find(article.displayTitle) {
                    article.displayTitle in it.text() && SemanticsActions.OnClick in it.config
                }
                fun list() = screen.find("article list") {
                    SemanticsActions.ScrollToIndex in it.config && SemanticsProperties.VerticalScrollAxisRange in it.config
                }
                fun swipe(article: Article, vararg stops: Float, finish: Int = MotionEvent.ACTION_UP, stepMillis: Long = 16L) {
                    val y = card(article).boundsInRoot.center.y
                    drag(root, stops.map { Offset(it, y) }, finish, stepMillis)
                    settle(root, 25)
                }
                settle(root, 20)
                tap(root, card(removeArticle).boundsInRoot.center)
                assertEquals(listOf(removeArticle.id to ArticlePress.Tap), presses)
                presses.clear()

                val cardCenter = card(badArticle).boundsInRoot.center.y
                drag(root, listOf(Offset(80f, cardCenter), Offset(92f, cardCenter - 11f), Offset(288f, cardCenter - 200f)))
                settle(root, 25)
                assertTrue(list().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
                assertEquals(originalIds, snapshot.value.articles.map { it.id })
                assertTrue(checkNotNull(list().config[SemanticsActions.ScrollToIndex].action).invoke(0))

                swipe(removeArticle, 130f, 196f, stepMillis = 4)
                assertEquals("A short flick leaves the card in place", originalIds, snapshot.value.articles.map { it.id })

                swipe(removeArticle, 62f, 298f, finish = MotionEvent.ACTION_CANCEL)
                assertEquals("A cancelled drag leaves the card in place", originalIds, snapshot.value.articles.map { it.id })

                swipe(removeArticle, 100f, 225f)
                assertTrue(checkNotNull(store.article(removeArticle.id)).dismissed)
                assertNull(store.article(removeArticle.id)?.rating)

                swipe(badArticle, 298f, 62f, 177f)
                assertEquals(ArticleRating.Bad, store.article(badArticle.id)?.rating)

                swipe(goodArticle, 180f, 20f)
                assertEquals(ArticleRating.Good, store.article(goodArticle.id)?.rating)
                val dismissed = setOf(removeArticle.id, badArticle.id, goodArticle.id)
                assertTrue(snapshot.value.articles.none { it.id in dismissed })
                queues.forEach { queue -> assertTrue(store.load(queue.id, false, 20).articles.none { it.id in dismissed }) }
                assertTrue("A drag does not open the article", presses.isEmpty())
            }
        }
    }

    private fun tap(root: View, position: Offset) {
        val start = SystemClock.uptimeMillis()
        dispatch(root, start, MotionEvent.ACTION_DOWN, position)
        settle(root, 2)
        dispatch(root, start, MotionEvent.ACTION_UP, position)
        settle(root, 2)
    }

    private fun drag(root: View, stops: List<Offset>, finish: Int = MotionEvent.ACTION_UP, stepMillis: Long = 16L) {
        val downTime = SystemClock.uptimeMillis()
        dispatch(root, downTime, MotionEvent.ACTION_DOWN, stops.first())
        for ((from, to) in stops.zipWithNext()) {
            for (step in 1..12) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMillis))
                dispatch(root, downTime, MotionEvent.ACTION_MOVE, from + (to - from) * (step / 12f))
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMillis))
        dispatch(root, downTime, finish, stops.last())
    }

    private fun dispatch(root: View, downTime: Long, action: Int, position: Offset) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, position.x, position.y, 0)
        try {
            root.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun settle(root: View, frames: Int) {
        repeat(frames) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            root.layoutAt(360, 800)
        }
    }
}
