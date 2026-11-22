package io.github.sasakiy84.qarry

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class WebsiteScopesTest {
    private val url = "https://example.com/training"

    @Test
    fun offersTheFullListAndLabeledTablesWithTheSameRowsInThePreviewAndSubscription() {
        val html = """
            <title>研修案内</title><main id="updates">
              <h2>科学技術</h2>
              <figure class="course-table"><div><table>
                <thead><tr><th>タイトル</th><th>内容</th></tr></thead>
                <tbody>
                  <tr><td><a href="/classes/science">科学の調べ方</a></td><td>科学の資料を探す</td></tr>
                  <tr><td><a href="/classes/standards">技術規格</a></td><td>規格の読み方</td></tr>
                </tbody>
              </table></div></figure>
              <h2>資料保存</h2>
              <figure class="course-table"><div><table>
                <thead><tr><th>タイトル</th><th>内容</th></tr></thead>
                <tbody>
                  <tr><td><a href="/classes/repair">資料を直す</a></td><td>基本の補修</td></tr>
                  <tr><td><a href="/classes/digital">デジタル化</a></td><td>撮影の準備</td></tr>
                </tbody>
              </table></div></figure>
            </main>
        """.trimIndent()

        val preview = requireNotNull(websiteScopes(Jsoup.parse(html, url), url, "\"tag\"", null))
        val wide = preview.options.single { it.label == "一覧全体" }
        val narrow = preview.options.single { it.label == "科学技術" }
        assertEquals(4, wide.feed.articles.size)
        assertEquals(listOf("科学の調べ方", "技術規格"), narrow.feed.articles.map { it.title })
        assertEquals("\"tag\"", narrow.feed.etag)
        assertTrue(wide.highlightIds.containsAll(narrow.highlightIds))
        val rendered = Jsoup.parse(preview.html)
        assertEquals(4, rendered.select("tr[data-qarry-item]").size)
        assertTrue(narrow.highlightIds.all { rendered.selectFirst("tr[data-qarry-item=$it]") != null })

        val changed = html.replace(
            "<tr><td><a href=\"/classes/standards\">技術規格</a></td><td>規格の読み方</td></tr>",
            """
                <tr><td><a href="/classes/standards">技術規格</a></td><td>規格の読み方</td></tr>
                <tr><td><a href="/classes/new">新しい研修</a></td><td>追加分</td></tr>
            """.trimIndent(),
        )
        val refreshed = requireNotNull(refreshWebsiteDocument(
            Jsoup.parse(changed, url), url, requireNotNull(narrow.feed.htmlItemSelector), narrow.feed.htmlWatch,
        ))
        assertEquals(listOf("科学の調べ方", "技術規格", "新しい研修"), refreshed.articles.map { it.title })
    }

    @Test
    fun offersDisjointCandidatesAndUsesAStaticPreviewWithoutTheSitesScriptsOrLinks() {
        val page = Jsoup.parse(
            """
            <main>
              <h2>お知らせ</h2>
              <section>
                <article data-qarry-item="999" onclick="alert(1)"><a href="/news/1">News 1</a></article>
                <article><a href="/news/2">News 2</a></article>
              </section>
              <h2>イベント</h2>
              <section>
                <article><a href="/events/1">Event 1</a></article>
                <article><a href="/events/2">Event 2</a></article>
              </section>
              <script src="https://cdn.example/sidebar.js"></script>
              <iframe src="https://cdn.example/frame"></iframe>
            </main>
            """.trimIndent(),
            url,
        )
        val preview = requireNotNull(websiteScopes(page, url, null, null))
        assertEquals(listOf("お知らせ", "イベント"), preview.options.map { it.label })
        assertTrue(preview.options[0].highlightIds.intersect(preview.options[1].highlightIds.toSet()).isEmpty())
        val rendered = Jsoup.parse(preview.html)
        assertTrue(rendered.select("script, iframe, [onclick], a[href], [data-qarry-item=999]").isEmpty())
        assertEquals(4, rendered.select("article[data-qarry-item]").size)
    }
}
