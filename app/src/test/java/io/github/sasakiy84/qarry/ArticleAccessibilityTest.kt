package io.github.sasakiy84.qarry

import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import org.junit.After
import org.junit.Assert.assertEquals
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
import android.view.KeyEvent as AndroidKeyEvent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArticleAccessibilityTest {
    @After
    fun restoreFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }

    private val article = Article(
        id = 1, feedTitle = "図書館のお知らせ", title = "図書館の新着資料と来月のイベントのお知らせ",
        url = null, body = "新しい資料を公開しました。図書館のイベントも開催します。", published = null,
        read = false, feedId = 1, viewedAt = null, dismissed = false, rating = null,
    )

    @Test
    fun articleCardExposesOpeningDestinationsAndSwipeActionsAtLargeTextSize() {
        RuntimeEnvironment.setFontScale(2f)
        val linkedArticle = article.copy(url = "https://127.0.0.1:1/article")
        val presses = mutableListOf<ArticlePress>()
        val dismissals = mutableListOf<ArticleRating?>()
        withScreen({
            CompositionLocalProvider(LocalArticleOpenMode provides ArticleOpenMode.Browser) {
                ArticlesPage(
                    LibrarySnapshot(articles = listOf(linkedArticle)), queueId = null, unread = false,
                    markingRead = false, onQueueFilter = { _, _ -> }, onUnread = {}, onMarkAllRead = {}, onAdd = {}, onMore = {},
                    onArticle = { _, press -> presses.add(press) },
                    onDismiss = { _, rating -> dismissals.add(rating); true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }) { decor, compose ->
            assertEquals(2f, compose.density.fontScale)
            val merged = compose.semanticsOwner.rootSemanticsNode.semanticTree()
            assertTrue(merged.any { it.text() == "記事" && SemanticsProperties.Heading in it.config })
            assertTrue(merged.any { it.text() == "すべて既読にする" && SemanticsActions.OnClick in it.config })
            val card = merged.single { SemanticsActions.CustomActions in it.config }
            val open = card.config[SemanticsActions.OnClick]
            val longPress = card.config[SemanticsActions.OnLongClick]
            assertEquals("デフォルトブラウザで記事を開く", open.label)
            assertEquals("アプリ内で記事を開く", longPress.label)
            assertTrue(checkNotNull(open.action).invoke())
            assertTrue(checkNotNull(longPress.action).invoke())
            assertEquals(listOf(ArticlePress.Tap, ArticlePress.LongPress), presses)
            val actions = card.config[SemanticsActions.CustomActions]
            assertEquals(
                listOf(
                    "すべてのキューから削除",
                    "Bad と評価してすべてのキューから削除",
                    "Good と評価してすべてのキューから削除",
                ),
                actions.map { it.label },
            )
            assertTrue(actions.first().action())
            settle(decor)
            assertEquals(listOf<ArticleRating?>(null), dismissals)

            val title = compose.semanticsOwner.unmergedRootSemanticsNode.semanticTree().single { it.text() == article.displayTitle }
            val result = mutableListOf<TextLayoutResult>()
            assertTrue(checkNotNull(title.config[SemanticsActions.GetTextLayoutResult].action).invoke(result))
            assertEquals(2, result.single().lineCount)
            assertTrue(title.boundsInRoot.height >= result.single().size.height - 1)
        }
    }

    @Test
    fun articleDetailRatingsCanBeReachedWithTabAndActivatedWithEnter() {
        RuntimeEnvironment.setFontScale(2f)
        val ratings = mutableListOf<ArticleRating>()
        withScreen({
            ArticlePage(
                article, onFeed = {}, onRemove = {}, onRate = { ratings.add(it) },
                onReadChange = {}, onOpen = {}, onShare = { _, _ -> }, modifier = Modifier.fillMaxSize(),
            )
        }) { decor, compose ->
            fun nodes() = compose.semanticsOwner.rootSemanticsNode.semanticTree()
            assertTrue(nodes().any { it.text() == article.displayTitle && SemanticsProperties.Heading in it.config })
            fun focused() = nodes().firstOrNull {
                it.text().isNotEmpty() && it.config.getOrElse(SemanticsProperties.Focused) { false }
            }?.text()
            val good = nodes().single { it.text() == "Good" }
            assertTrue(checkNotNull(good.config[SemanticsActions.RequestFocus].action).invoke())
            settle(decor)
            assertEquals("Good", focused())
            press(decor, KEYCODE_TAB)
            settle(decor)
            assertEquals("Bad", focused())
            press(decor, KEYCODE_ENTER)
            assertEquals(listOf(ArticleRating.Bad), ratings)
        }
    }

    private fun withScreen(content: @Composable () -> Unit, check: (View, RootForTest) -> Unit) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true).use { controller ->
            val activity = controller.get()
            activity.setContent { MaterialTheme { content() } }
            val decor = activity.window.decorView
            settle(decor)
            val compose = decor.views().single { it is RootForTest }
            compose.requestFocusFromTouch()
            check(decor, compose as RootForTest)
        }
    }

    private fun press(view: View, code: Int) {
        view.dispatchKeyEvent(AndroidKeyEvent(0, 0, ACTION_DOWN, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD))
        view.dispatchKeyEvent(AndroidKeyEvent(0, 0, ACTION_UP, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD))
    }

    private fun settle(view: View) {
        repeat(14) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            view.layoutAt(320, 800)
            Thread.sleep(4)
        }
    }
}
