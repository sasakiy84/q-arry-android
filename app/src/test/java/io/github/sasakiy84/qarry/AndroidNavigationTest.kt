package io.github.sasakiy84.qarry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidFeedRegistrationTest : AndroidUxTestScreen() {
    @Test
    fun toolbarAndSystemBackBothReturnFromPreviewToTheEnteredUrl() = withLibrary { _, library ->
        Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true).use { controller ->
            val activity = controller.get()
            activity.setContent { MaterialTheme { QarryScreen(library) {} } }
            val screen = Screen(activity.window.decorView)
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
            assertTrue(screen.text("フィード").text() == "フィード")
            assertNotNull(screen.find { it.text().contains("登録テスト") })
            activity.onBackPressedDispatcher.onBackPressed()
            screen.text("未読のみ")
            assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidQueueNavigationTest : AndroidUxTestScreen() {
    @Test
    fun queueAndFeedLinksReturnInTheOrderOpenedAndTabsKeepTheirFilters() = withLibrary { store, library ->
        store.queueName(null, "資料キュー")
        val queue = store.load(null, false, 10).queues.single()
        store.subscribe(FeedDocument(address, "テスト配信元", null, listOf(FeedArticle("a", "資料記事", null, null, null))), queue.id)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true).use { controller ->
            val activity = controller.get()
            activity.setContent { MaterialTheme { QarryScreen(library) {} } }
            val screen = Screen(activity.window.decorView)

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
            assertTrue(screen.find { it.text() == "資料キュー" && it.config.getOrElse(SemanticsProperties.Selected) { false } }.text() == "資料キュー")
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
class AndroidQueueFormTest : AndroidUxTestScreen() {
    @Test
    fun keyboardDraftSurvivesRecreationAndQueueCreationReturnsToMembership() = withLibrary { store, _ ->
        store.subscribe(FeedDocument(address, "テスト配信元", null, emptyList()), null)
        Robolectric.buildActivity(MainActivity::class.java).setup().windowFocusChanged(true).use { controller ->
            val original = Screen(controller.get().window.decorView)
            original.tap("フィード")
            original.tapContaining("テスト配信元")
            original.tap("キューを設定")
            val first = original.dialogWith("キューがありません。")
            first.tap("キューを作成")
            original.dialogWith("キュー名").enterText("入力中のキュー")

            controller.recreate().windowFocusChanged(true)
            val screen = Screen(controller.get().window.decorView)
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
class AndroidPreviewLayoutTest : AndroidUxTestScreen() {
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
            val screen = Screen(root, width = 720, height = 360)
            val button = screen.text("この範囲を登録")
            val preview = screen.find { SemanticsProperties.HideFromAccessibility in it.config }
            assertTrue(preview.boundsInRoot.width > 0f && preview.boundsInRoot.height > 0f)
            assertTrue(preview.boundsInRoot.right <= 360f)
            assertEquals(1, root.views().filterIsInstance<WebView>().count())
            assertTrue(button.boundsInRoot.left >= 360f && button.boundsInRoot.bottom <= 360f)
            screen.tap("この範囲を登録")
            assertEquals(website.options.first().feed, registered)
        }
    }
}

abstract class AndroidUxTestScreen {
    protected val address = "https://127.0.0.1:1/updates"
    protected val html = """
        <title>登録テスト</title><main id="updates">
          <article><a href="/posts/1">最初の記事</a></article>
          <article><a href="/posts/2">次の記事</a></article>
        </main>
    """.trimIndent()

    protected fun withLibrary(block: (FeedStore, FeedLibrary) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("q-arry.db")
        val client = FeedClient { request ->
            object : HttpURLConnection(URI(request).toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = if (request == address) 200 else 404
                override fun getContentType() = "text/html; charset=UTF-8"
                override fun getInputStream() = ByteArrayInputStream(html.toByteArray())
            }
        }
        FeedStore(context).use { block(it, FeedLibrary(it, client)) }
    }

    protected class Screen(private val root: View, private val width: Int = 360, private val height: Int = 800) {
        private val canvas by lazy { Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)) }

        fun text(label: String) = find { it.text() == label }

        fun tap(label: String) = tapMatching { it.text() == label }

        fun tapContaining(label: String) = tapMatching { it.text().contains(label) }

        fun tapDescription(label: String) = tapMatching {
            label in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        }

        private fun tapMatching(matches: (SemanticsNode) -> Boolean) {
            val node = find { matches(it) && SemanticsActions.OnClick in it.config }
            assertTrue(checkNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        }

        fun enterText(value: String) {
            val action = find { SemanticsActions.SetText in it.config }.config[SemanticsActions.SetText].action
            assertTrue(checkNotNull(action).invoke(AnnotatedString(value)))
        }

        fun submitFromKeyboard() {
            val action = find { SemanticsActions.OnImeAction in it.config }.config[SemanticsActions.OnImeAction].action
            assertTrue(checkNotNull(action).invoke())
        }

        fun inputValue() = find { SemanticsProperties.EditableText in it.config }.config[SemanticsProperties.EditableText].text

        fun dialogWith(label: String): Screen {
            repeat(80) {
                draw()
                val dialog = ShadowDialog.getLatestDialog()
                if (dialog?.isShowing == true) {
                    val window = checkNotNull(dialog.window)
                    val screen = Screen(window.decorView, width, height)
                    screen.draw()
                    if (screen.nodes().any { it.text().contains(label) }) return screen
                }
                Thread.sleep(8)
            }
            error("Dialog not shown: $label")
        }

        fun find(predicate: (SemanticsNode) -> Boolean): SemanticsNode {
            repeat(100) {
                draw()
                nodes().firstOrNull(predicate)?.let { return it }
                Thread.sleep(10)
            }
            error("Expected screen not shown. Visible text: ${nodes().map { it.text() }.filter(String::isNotBlank)}")
        }

        private fun draw() {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            root.draw(canvas)
        }

        private fun nodes() = root.views().filterIsInstance<RootForTest>()
            .flatMap { it.semanticsOwner.rootSemanticsNode.tree() }.toList()
    }

    companion object {
        fun View.views(): Sequence<View> = sequence {
            yield(this@views)
            if (this@views is ViewGroup) repeat(childCount) { yieldAll(getChildAt(it).views()) }
        }

        fun SemanticsNode.tree(): Sequence<SemanticsNode> = sequenceOf(this) + children.asSequence().flatMap { it.tree() }

        fun SemanticsNode.text() = config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }
    }
}
