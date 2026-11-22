package io.github.sasakiy84.qarry

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.runBlocking
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
class WebsiteScopeNavigationTest {
    private val address = "https://127.0.0.1:1/courses"

    @Test
    fun showsRecoveryAndWritesTheReplacementTableOnlyAfterConfirmation() {
        val originalTable = table("技術", 1, 2)
        val replacement = table("新しい表", 5, 6)
        var html = page(originalTable)
        val client = fixtureWebsiteClient(address) { html }
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
                val screen = ComposeScreen(activity.window.decorView)
                screen.tap("フィード")
                screen.tapContaining("合成研修ページ")
                screen.tap("範囲を選び直す")
                screen.tapMatching("技術 2件") { it.text().startsWith("技術") && it.text().endsWith("2件") }
                screen.text("新しい表 5")
                assertTrue(store.feedArticles(feedId, 10).articles.none { it.title.orEmpty().startsWith("新しい表") })
                screen.tap("更新範囲を変更")
                screen.text("監視範囲を変更しました")
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
}
