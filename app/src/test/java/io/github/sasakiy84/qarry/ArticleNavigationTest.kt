package io.github.sasakiy84.qarry

import android.content.Intent
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArticleNavigationTest {
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
                val screen = ComposeScreen(activity.window.decorView)
                fun list(): SemanticsNode = screen.find("記事一覧") {
                    SemanticsActions.ScrollToIndex in it.config && SemanticsProperties.VerticalScrollAxisRange in it.config
                }
                fun scrollTo(index: Int, articleTitle: String): Float {
                    assertTrue(checkNotNull(list().config[SemanticsActions.ScrollToIndex].action).invoke(index))
                    screen.find(articleTitle) { articleTitle in it.text() }
                    val current = list()
                    val firstPosition = current.scrollOffset()
                    assertTrue(checkNotNull(current.config[SemanticsActions.ScrollBy].action).invoke(0f, 28f))
                    screen.advanceFrames(32)
                    return list().scrollOffset().also { assertTrue(it > firstPosition) }
                }
                fun backToList(position: Float) {
                    activity.onBackPressedDispatcher.onBackPressed()
                    screen.find("一覧のスクロール位置 $position") {
                        SemanticsActions.ScrollToIndex in it.config &&
                            SemanticsProperties.VerticalScrollAxisRange in it.config &&
                            abs(it.scrollOffset() - position) < 0.01f
                    }
                }

                screen.find("未読の記事") { "記事 0" in it.text() }
                assertTrue(screen.nodes().any { "更新検知 " in it.text() })
                assertTrue(screen.nodes().none { "記事 1" in it.text() })
                screen.tap("すべて既読にする")
                screen.dialogWith("すべて既読にしますか？").tap("既読にする")
                screen.text("未読の記事はありません")
                screen.tap("未読のみ")

                val articlesPosition = scrollTo(14, title)
                screen.tapContaining(title)
                screen.text("共有")
                assertTrue(screen.nodes().any { it.text().startsWith("公開 ") })
                screen.tap("サンプルフィード")
                screen.text("記事一覧")
                val feedPosition = scrollTo(15, title)
                screen.tapContaining(title)
                screen.text("共有")
                backToList(feedPosition)

                activity.onBackPressedDispatcher.onBackPressed()
                screen.text("共有")
                backToList(articlesPosition)
                screen.tap("設定")
                screen.tap("閲覧履歴")
                val historyPosition = scrollTo(16, "記事 16")
                screen.tapContaining("記事 16")
                screen.find("履歴からの記事詳細") {
                    it.text() == "記事 16" && SemanticsProperties.Heading in it.config
                }
                backToList(historyPosition)
                screen.tap("記事")
                assertEquals(articlesPosition, list().scrollOffset(), 0.01f)
                screen.tapContaining(title)
                screen.tap("共有")
                val chooser = checkNotNull(shadowOf(activity).nextStartedActivity)
                val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
                assertEquals("$title\n$url", send.getStringExtra(Intent.EXTRA_TEXT))
                backToList(articlesPosition)
            }
        }
    }

    private fun SemanticsNode.scrollOffset(): Float = config[SemanticsProperties.VerticalScrollAxisRange].value()
}
