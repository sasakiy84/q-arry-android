package io.github.sasakiy84.qarry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebsiteScopeNavigationTest {
    private val address = "https://127.0.0.1:1/courses"
    private val canvas by lazy { Canvas(Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)) }

    @Test
    fun showsRecoveryAndWritesTheReplacementTableOnlyAfterConfirmation() {
        val originalTable = table("技術", 1, 2)
        val replacement = table("新しい表", 5, 6)
        var html = page(originalTable)
        val client = FeedClient { requested ->
            object : HttpURLConnection(URI(requested).toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = if (requested == address) 200 else 404
                override fun getContentType() = "text/html; charset=UTF-8"
                override fun getInputStream() = ByteArrayInputStream(html.toByteArray())
            }
        }
        FeedStore(RuntimeEnvironment.getApplication()).use { store ->
            val library = FeedLibrary(store, client)
            val selected = client.inspectWebsite(address).options.single { it.label == "技術" }.feed
            val feedId = store.subscribe(selected, null)
            val viewed = store.feedArticles(feedId, 10).articles.first()
            store.recordView(viewed.id)

            html = page("<p>補足説明</p>${table("技術", 1, 2, 7)}")
            runBlocking { library.refresh(feedId) }
            assertEquals(setOf("技術 1", "技術 2", "技術 7"), store.feedArticles(feedId, 10).articles.map { it.title }.toSet())

            html = page(replacement + originalTable)
            runBlocking { library.refresh(feedId) }
            assertEquals(WEBSITE_SCOPE_CHANGED, store.subscriptions().single().error)
            assertEquals(setOf("技術 1", "技術 2", "技術 7"), store.feedArticles(feedId, 10).articles.map { it.title }.toSet())

            Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true).use { controller ->
                val activity = controller.get()
                activity.setContent { MaterialTheme { QarryScreen(library) {} } }
                val root = activity.window.decorView
                root.click { it == "フィード" }
                root.click { "合成研修ページ" in it }
                root.click { it == "範囲を選び直す" }
                root.click { it.startsWith("技術") && it.endsWith("2件") }
                root.await { it == "新しい表 5" }
                assertTrue(store.feedArticles(feedId, 10).articles.none { it.title.orEmpty().startsWith("新しい表") })
                root.click { it == "更新範囲を変更" }
                root.await { it == "監視範囲を変更しました" }
                assertNull(store.subscriptions().single().error)
                assertEquals(setOf("技術 1", "技術 2", "技術 7", "新しい表 5", "新しい表 6"),
                    store.feedArticles(feedId, 10).articles.map { it.title }.toSet())
                assertTrue(checkNotNull(store.article(viewed.id)).read)
            }

            runBlocking { library.refresh(feedId) }
            assertNull(store.subscriptions().single().error)
        }
    }

    private fun page(technology: String): String = """
        <title>合成研修ページ</title><main id="courses"><h2 id="tech">技術</h2>$technology
        <h2 id="history">歴史</h2>${table("歴史", 3, 4)}</main>
    """.trimIndent()

    private fun table(title: String, vararg ids: Int): String = """
        <figure class="course-table"><table><thead><tr><th>タイトル</th><th>概要</th></tr></thead>
        <tbody>${ids.joinToString("") { "<tr><td><a href='/courses/$it'>$title $it</a></td><td>説明</td></tr>" }}</tbody>
        </table></figure>
    """.trimIndent()

    private fun View.click(matches: (String) -> Boolean) {
        val node = await { text -> matches(text) }
        assertTrue(checkNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun View.await(matches: (String) -> Boolean): SemanticsNode {
        repeat(80) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            layout(0, 0, 360, 800)
            draw(canvas)
            nodes().firstOrNull { matches(it.text()) }?.let { return it }
            Thread.sleep(12)
        }
        error("Expected screen not shown. Visible text: ${nodes().map { it.text() }.filter(String::isNotBlank)}")
    }

    private fun View.nodes(): List<SemanticsNode> = viewTree().filterIsInstance<RootForTest>()
        .flatMap { it.semanticsOwner.rootSemanticsNode.tree() }.toList()

    private fun View.viewTree(): Sequence<View> = sequence {
        yield(this@viewTree)
        if (this@viewTree is ViewGroup) repeat(childCount) { yieldAll(getChildAt(it).viewTree()) }
    }

    private fun SemanticsNode.tree(): Sequence<SemanticsNode> = sequenceOf(this) + children.asSequence().flatMap { it.tree() }

    private fun SemanticsNode.text(): String = config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }
}
