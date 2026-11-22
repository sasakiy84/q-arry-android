package io.github.sasakiy84.qarry

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import org.jsoup.Jsoup
import org.junit.Assert.*
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
class AndroidFeedRegistrationTest {
    @Test
    fun toolbarAndSystemBackBothReturnFromPreviewToTheEnteredUrl() = withLibrary { _, library ->
        Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true).use { controller ->
            val activity = controller.get()
            activity.setContent { MaterialTheme { QarryScreen(library) {} } }
            val screen = ComposeScreen(activity.window.decorView)
            screen.tap("フィード")
            screen.tap("追加")
            screen.enterText(address)
            screen.submitFromKeyboard()
            screen.text("登録する範囲")
            screen.tapDescription("戻る")
            assertEquals(address, screen.inputValue())

            screen.tap("登録候補を確認")
            screen.text("登録する範囲")
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(address, screen.inputValue())
            screen.tap("登録候補を確認")
            screen.tap("この範囲を登録")
            screen.tap("追加")
            screen.tapDescription("戻る")
            screen.find("フィード一覧に新しい購読が表示される") { "登録テスト" in it.text() }
            activity.onBackPressedDispatcher.onBackPressed()
            screen.text("未読のみ")
            assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidQueueNavigationTest {
    @Test
    fun queueAndFeedLinksReturnInTheOrderOpenedAndTabsKeepTheirFilters() = withLibrary { store, library ->
        store.queueName(null, "資料キュー")
        val queue = store.load(null, false, 10).queues.single()
        store.subscribe(FeedDocument(address, "テスト配信元", null, listOf(FeedArticle("a", "資料記事", null, null, null))), queue.id)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true).use { controller ->
            val activity = controller.get()
            activity.setContent { MaterialTheme { QarryScreen(library) {} } }
            val screen = ComposeScreen(activity.window.decorView)

            screen.tap("資料キュー")
            screen.text("すべて")
            assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
            screen.tap("キュー")
            screen.text("新規作成")
            screen.tapContaining("資料キュー")
            screen.tapContaining("資料記事")
            screen.tap("テスト配信元")
            screen.tap("資料キュー")
            screen.text("未読のみ")

            screen.tapDescription("戻る")
            screen.text("フィード詳細")
            activity.onBackPressedDispatcher.onBackPressed()
            screen.text("未読に戻す")
            screen.tapDescription("戻る")
            screen.text("未読の記事はありません")
            activity.onBackPressedDispatcher.onBackPressed()
            screen.text("新規作成")
            screen.tap("設定")
            screen.tap("閲覧履歴")
            screen.find { it.text().contains("資料記事") }
            activity.onBackPressedDispatcher.onBackPressed()
            screen.text("すべて")
            screen.find("資料キューが選択されたまま") {
                it.text() == "資料キュー" && it.config.getOrElse(SemanticsProperties.Selected) { false }
            }
            assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
            screen.tap("設定")
            screen.find { it.text().contains("資料記事") }
            screen.tap("記事")
            screen.tap("未読のみ")
            screen.tapContaining("資料記事")
            screen.tap("テスト配信元")
            screen.tap("購読を解除")
            screen.dialogWith("フィードを解除しますか？").tap("解除")
            screen.text("最初のフィードを登録")
            assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidQueueFormTest {
    @Test
    fun keyboardDraftSurvivesRecreationAndQueueCreationReturnsToMembership() = withLibrary { store, _ ->
        store.subscribe(FeedDocument(address, "テスト配信元", null, emptyList()), null)
        Robolectric.buildActivity(MainActivity::class.java).setup().windowFocusChanged(true).use { controller ->
            val original = ComposeScreen(controller.get().window.decorView)
            original.tap("フィード")
            original.tapContaining("テスト配信元")
            original.tap("キューを設定")
            val first = original.dialogWith("キューがありません。")
            first.tap("キューを作成")
            original.dialogWith("キュー名").enterText("入力中のキュー")

            controller.recreate().windowFocusChanged(true)
            val screen = ComposeScreen(controller.get().window.decorView)
            val form = screen.dialogWith("キュー名")
            assertEquals("入力中のキュー", form.inputValue())
            form.tap("保存")
            val membership = screen.dialogWith("入力中のキュー")
            membership.tap("入力中のキュー")
            membership.tap("保存")
            screen.tap("入力中のキュー")
            screen.text("未読の記事はありません")
            screen.tapDescription("戻る")
            screen.text("フィード詳細")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w720dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidPreviewLayoutTest {
    @Test
    fun landscapeKeepsTheWebPreviewAndConfirmationVisible() {
        val website = checkNotNull(websiteScopes(Jsoup.parse(html, address), address, null, null))
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var registered: FeedDocument? = null
            activity.setContent {
                MaterialTheme {
                    WebsiteScopePage(website, false, false, null, {}, { registered = it }, Modifier.fillMaxSize())
                }
            }
            val root = activity.window.decorView
            val screen = ComposeScreen(root, width = 720, height = 360)
            val button = screen.text("この範囲を登録")
            val preview = screen.find { SemanticsProperties.HideFromAccessibility in it.config }
            assertTrue(preview.boundsInRoot.width > 0f && preview.boundsInRoot.height > 0f)
            assertTrue(button.boundsInRoot.left >= preview.boundsInRoot.right && button.boundsInRoot.bottom <= 360f)
            screen.tap("この範囲を登録")
            assertEquals(website.options.first().feed, registered)
        }
    }
}

private val address = "https://127.0.0.1:1/updates"
private val html = """
    <title>登録テスト</title><main id="updates">
      <article><a href="/posts/1">最初の記事</a></article>
      <article><a href="/posts/2">次の記事</a></article>
    </main>
""".trimIndent()

private fun withLibrary(block: (FeedStore, FeedLibrary) -> Unit) {
    val context = RuntimeEnvironment.getApplication()
    context.deleteDatabase("q-arry.db")
    val client = fixtureWebsiteClient(address) { html }
    FeedStore(context).use { block(it, FeedLibrary(it, client)) }
}
