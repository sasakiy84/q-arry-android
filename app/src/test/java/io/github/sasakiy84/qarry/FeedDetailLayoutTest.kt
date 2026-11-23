package io.github.sasakiy84.qarry

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedDetailLayoutTest {
    @Test
    fun keepsTheArticleListAndFeedControlsReachableWithLargeTextOnANarrowWindow() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            val title = "図書館のデジタル資料と研究活動についてのお知らせ"
            val articleTitle = "新しい資料を公開しました"
            FeedStore(RuntimeEnvironment.getApplication()).use { store ->
                val feedId = store.subscribe(
                    FeedDocument(
                        "https://example.org/feed", title, "https://example.org/",
                        listOf(FeedArticle("entry", articleTitle, null, "新着資料の案内です。", 2000)),
                    ),
                    null,
                )
                repeat(5) { store.queueName(null, "読書・調査のための資料キュー ${it + 1}") }
                store.setMembership(feedId, store.load(null, false, 1).queues.map { it.id }.toSet())

                Robolectric.buildActivity(MainActivity::class.java).setup().windowFocusChanged(true).use { controller ->
                    val screen = ComposeScreen(controller.get().window.decorView, width = 320)
                    screen.tap("フィード")
                    screen.tapContaining(title)
                    val heading = screen.find(title) { it.text() == title && SemanticsProperties.Heading in it.config }
                    val menu = screen.find("フィードのその他の操作") {
                        "フィードのその他の操作" in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } &&
                            SemanticsActions.OnClick in it.config
                    }
                    assertTrue(heading.boundsInRoot.right <= menu.boundsInRoot.left)
                    assertTrue(menu.boundsInRoot.right <= 320f)
                    val card = screen.find(articleTitle) { articleTitle in it.text() && SemanticsActions.OnClick in it.config }
                    assertTrue(card.boundsInRoot.height > 0f && card.boundsInRoot.bottom <= 800f)
                    assertFalse(title in card.text())
                    screen.text("キューを設定")
                    screen.text("サイト")
                    screen.text("一括削除")
                    screen.tapDescription("フィードのその他の操作")
                    val actions = screen.dialogWith("フィードの操作")
                    actions.text("フィードURLを開く")
                    actions.tap("フィルターを設定")
                    screen.dialogWith("キーワードフィルター").text("キーワードフィルター")
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
