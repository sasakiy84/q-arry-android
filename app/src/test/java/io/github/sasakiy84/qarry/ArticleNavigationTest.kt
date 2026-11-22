package io.github.sasakiy84.qarry

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArticleNavigationTest {
    private val canvas by lazy { Canvas(Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)) }

    @Test
    fun sharesArticleAndRestoresEachArticleListAtTheSameScrollOffset() {
        val url = "https://127.0.0.1:1/article?issue=3"
        val title = "選択した記事"
        FeedStore(RuntimeEnvironment.getApplication()).use { store ->
            store.subscribe(FeedDocument(
                "https://publisher.example/feed", "サンプルフィード", null,
                List(40) { index ->
                    FeedArticle(
                        index.toString(), if (index == 14) title else "記事 $index",
                        if (index == 14) url else null, "記事の説明", if (index == 0) null else (40 - index) * 1000L,
                    )
                },
            ), null)
            store.load(null, false, 40).articles.forEachIndexed { index, article ->
                store.recordView(article.id, (40 - index) * 1_000L)
            }
            store.setRead(store.load(null, false, 1).articles.single().id, false)
            Robolectric.buildActivity(MainActivity::class.java).setup().windowFocusChanged(true).use { controller ->
                val activity = controller.get()
                val root = activity.window.decorView
                fun scrollableList() = root.nodes().firstOrNull { SemanticsActions.ScrollToIndex in it.config }
                fun scrollTo(index: Int, articleTitle: String): Float {
                    waitFor(root, "記事一覧") { scrollableList() != null }
                    val initialList = checkNotNull(scrollableList())
                    assertTrue(checkNotNull(initialList.config[SemanticsActions.ScrollToIndex].action).invoke(index))
                    waitFor(root, articleTitle) { root.nodes().any { it.text().contains(articleTitle) } }
                    val list = checkNotNull(scrollableList())
                    val firstPosition = list.scrollOffset()
                    assertTrue(checkNotNull(list.config[SemanticsActions.ScrollBy].action).invoke(0f, 28f))
                    advance(root, 32)
                    return checkNotNull(scrollableList()).scrollOffset().also { assertTrue(it > firstPosition) }
                }
                fun backToList(position: Float) {
                    activity.onBackPressedDispatcher.onBackPressed()
                    waitFor(root, "記事一覧の復元") { scrollableList() != null }
                    assertEquals(position, checkNotNull(scrollableList()).scrollOffset(), 0.01f)
                }

                waitFor(root, "未読の記事") { root.nodes().any { it.text().contains("記事 0") } }
                assertTrue(root.nodes().any { it.text().contains("更新検知 ") })
                assertTrue(root.nodes().none { it.text().contains("記事 1") })
                root.click { it == "すべて既読にする" }
                waitFor(root, "すべて既読にする確認ダイアログ") { ShadowDialog.getLatestDialog()?.isShowing == true }
                val dialog = checkNotNull(ShadowDialog.getLatestDialog().window).decorView
                waitFor(dialog, "既読にする") { dialog.nodes().any { it.text() == "既読にする" } }
                dialog.click { it == "既読にする" }
                waitFor(root, "未読一覧が空") { root.nodes().any { it.text() == "未読の記事はありません" } }
                root.click { it == "未読のみ" }

                val articlesPosition = scrollTo(14, title)
                root.click { it.contains(title) }
                waitFor(root, "記事詳細") { root.nodes().any { it.text() == "共有" } }
                assertTrue(root.nodes().any { it.text().startsWith("公開 ") })
                root.click { it == "サンプルフィード" }
                waitFor(root, "フィード詳細") { root.nodes().any { it.text() == "記事一覧" } }
                val feedPosition = scrollTo(15, title)
                root.click { it.contains(title) }
                waitFor(root, "記事詳細") { root.nodes().any { it.text() == "共有" } }
                backToList(feedPosition)

                activity.onBackPressedDispatcher.onBackPressed()
                waitFor(root, "元の記事詳細") { root.nodes().any { it.text() == "共有" } }
                backToList(articlesPosition)
                root.click { it == "設定" }
                waitFor(root, "設定") { root.nodes().any { it.text() == "閲覧履歴" } }
                root.click { it == "閲覧履歴" }
                val historyPosition = scrollTo(16, "記事 16")
                root.click { it.contains("記事 16") }
                waitFor(root, "履歴からの記事詳細") {
                    root.nodes().any { it.text() == "記事 16" && SemanticsProperties.Heading in it.config }
                }
                backToList(historyPosition)
                root.click { it == "記事" }
                waitFor(root, "記事一覧") { scrollableList() != null }
                assertEquals(articlesPosition, checkNotNull(scrollableList()).scrollOffset(), 0.01f)
                root.click { it.contains(title) }
                waitFor(root, "記事詳細の共有") { root.nodes().any { it.text() == "共有" } }
                root.click { it == "共有" }
                val chooser = checkNotNull(shadowOf(activity).nextStartedActivity)
                val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
                assertEquals("$title\n$url", send.getStringExtra(Intent.EXTRA_TEXT))
                backToList(articlesPosition)
            }
        }
    }

    private fun SemanticsNode.scrollOffset(): Float = config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun View.nodes(): List<SemanticsNode> = descendants().filterIsInstance<RootForTest>()
        .flatMap { it.semanticsOwner.rootSemanticsNode.allNodes() }.toList()

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) repeat(childCount) { yieldAll(getChildAt(it).descendants()) }
    }

    private fun SemanticsNode.allNodes(): Sequence<SemanticsNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.allNodes() }

    private fun SemanticsNode.text() = config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }

    private fun View.click(matches: (String) -> Boolean) {
        val node = nodes().first { matches(it.text()) && SemanticsActions.OnClick in it.config }
        assertTrue(checkNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun waitFor(view: View, label: String, condition: () -> Boolean) {
        repeat(40) {
            advance(view, 4)
            if (condition()) return
        }
        error("Timed out waiting for $label. Visible text: ${view.nodes().map { it.text() }.filter(String::isNotBlank)}")
    }

    private fun advance(root: View, frames: Int) {
        repeat(frames) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 800)
            Thread.sleep(8)
        }
        root.draw(canvas)
    }
}
