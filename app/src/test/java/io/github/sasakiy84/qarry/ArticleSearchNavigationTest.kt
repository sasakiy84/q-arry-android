package io.github.sasakiy84.qarry

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArticleSearchNavigationTest {
    @Test
    fun findsSavedBodyTextOpensItAndShowsSearchStorageWithVectorsOff() {
        val url = "https://127.0.0.1:1/article"
        val context = RuntimeEnvironment.getApplication()
        FeedStore(context).use { store ->
            store.subscribe(
                FeedDocument("https://example.org/feed", "資料配信", null,
                    listOf(FeedArticle("entry", "今週の資料", url, "フィードの説明", null))),
                null,
            )
            val id = store.load(null, false, 1).articles.single().id
            val saved = "新しく公開した資料に、地域の方言の聞き取り調査を収録しています。"
            store.saveArchive(ArchiveCandidate(id, url), saved, Long.MAX_VALUE)
            Robolectric.buildActivity(MainActivity::class.java).setup().windowFocusChanged(true).use { controller ->
                val activity = controller.get()
                val screen = ComposeScreen(activity.window.decorView)
                screen.tapDescription("記事検索")
                screen.enterText("聞き取り調査")
                screen.submitFromKeyboard()
                screen.tapContaining("今週の資料")
                screen.text(saved)
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals("聞き取り調査", screen.inputValue())
                activity.onBackPressedDispatcher.onBackPressed()
                screen.tap("設定")
                screen.find { it.text().startsWith("全文インデックス 1件") }
                val toggle = screen.find("ベクトル検索オフ") {
                    "ベクトル検索" in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } &&
                        SemanticsProperties.ToggleableState in it.config
                }
                assertEquals(ToggleableState.Off, toggle.config[SemanticsProperties.ToggleableState])
            }
        }
    }
}
