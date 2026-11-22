package io.github.sasakiy84.qarry

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HtmlFeedTest {
    private val pageUrl = "https://museum.example/whats-new"

    @Test
    fun infersTheRepeatedArticleCardsAndExtractsOnlyMetadataThatExists() {
        val feed = requireNotNull(extractHtmlFeed(fixture("news-cards"), pageUrl))
        val first = feed.articles[0]
        assertEquals("https://museum.example/archive/news/42", first.url)
        assertEquals("企画展が始まります", first.title)
        assertEquals("https://museum.example/images/42.webp", first.thumbnailUrl)
        assertEquals("展示室に新しい資料が加わりました。", first.body)

        val second = feed.articles[1]
        assertEquals("臨時休館のお知らせ", second.title)
        assertNull(second.body)
        assertNull(second.thumbnailUrl)

        val third = feed.articles[2]
        assertEquals("夜間開館", third.title)
        assertEquals("https://museum.example/images/44.jpg", third.thumbnailUrl)
        assertEquals(3, feed.articles.size)
    }

    @Test
    fun urlAloneIsEnoughAndQueryParametersIdentifyDifferentArticles() {
        val feed = requireNotNull(extractHtmlFeed(fixture("url-only"), pageUrl))
        assertEquals(listOf("$pageUrl?entry=70", "$pageUrl?entry=71"), feed.articles.map { it.url })
        assertTrue(feed.articles.all { it.title == null && it.body == null && it.thumbnailUrl == null })
    }

    @Test
    fun treatsEachLinkedDataRowAsAnItemAcrossRepeatedTables() {
        val html = """
            <main id="updates">
              <figure class="course-table"><div><table>
                <thead><tr><th>タイトル</th><th>内容</th><th>時間</th></tr></thead>
                <tbody>
                  <tr><td><a href="/courses/1">調べ方</a></td><td>資料を探す手順</td><td>20分</td></tr>
                  <tr><td><a href="/courses/2">データの活用</a></td><td>データを比較します</td><td>10分</td></tr>
                </tbody>
              </table></div></figure>
              <figure class="course-table"><div><table>
                <thead><tr><th>タイトル</th><th>内容</th><th>時間</th></tr></thead>
                <tbody>
                  <tr><td><a href="/courses/3">資料保存</a></td><td>保存環境を見直します</td><td>30分</td></tr>
                  <tr><td><a href="/courses/4">デジタル化</a></td><td></td><td>15分</td></tr>
                </tbody>
              </table></div></figure>
            </main>
        """.trimIndent()
        val page = Jsoup.parse(html, pageUrl)
        val found = requireNotNull(extractHtmlFeed(page, pageUrl))
        assertEquals((1..4).map { "https://museum.example/courses/$it" }, found.articles.map { it.url })
        assertEquals(listOf("調べ方", "データの活用", "資料保存", "デジタル化"), found.articles.map { it.title })
        assertEquals(listOf("資料を探す手順", "データを比較します", "保存環境を見直します", null), found.articles.map { it.body })
        assertEquals(found.articles, extractHtmlFeed(page, pageUrl, "#updates > figure.course-table")?.articles)

        val updated = Jsoup.parse(html, pageUrl)
        updated.select("tbody > tr").dropLast(1).forEach { it.remove() }
        val refreshed = requireNotNull(extractHtmlFeed(updated, pageUrl, found.htmlItemSelector))
        assertEquals("デジタル化", refreshed.articles.single().title)
    }

    @Test
    fun theSavedSelectorStillWorksWhenTheListShrinksToOneArticle() {
        val first = requireNotNull(extractHtmlFeed(fixture("news-cards"), pageUrl))
        val updated = requireNotNull(
            extractHtmlFeed(fixture("news-single-after-refresh"), pageUrl, requireNotNull(first.htmlItemSelector)),
        )
        assertEquals("https://museum.example/archive/news/45", updated.articles.single().url)
        assertEquals(first.htmlItemSelector, updated.htmlItemSelector)
    }

    @Test
    fun websiteRowsReadExplicitTimesButDoNotInferADayFromAPublicationMonth() {
        val html = """
            <main id="updates"><table>
              <thead><tr><th>記事</th><th>公開年月</th><th>更新日時</th></tr></thead>
              <tbody>
                <tr><td><a href="/news/1">第一回</a></td><td>令和6（2024）年7月</td>
                    <td><time datetime="2026-11-11T13:25:00+09:00">11月11日</time></td></tr>
                <tr><td><a href="/news/2">第二回</a></td><td>令和7（2025）年3月</td><td></td></tr>
              </tbody>
            </table></main>
        """.trimIndent()
        val (dated, undated) = requireNotNull(extractHtmlFeed(Jsoup.parse(html, pageUrl), pageUrl)).articles
        assertEquals(Instant.parse("2026-11-11T04:25:00Z").toEpochMilli(), dated.updatedAt)
        assertNull(dated.publishedTime)
        assertNull(undated.updatedAt)
        assertNull(undated.publishedTime)
    }

    @Test
    fun doesNotInferAFeedFromMenusOrAJavaScriptShell() {
        val navigation = """
            <nav><a href="/news/1">News</a><a href="/news/2">Events</a></nav>
            <main><div id="app">読み込み中</div></main>
        """.trimIndent()
        assertNull(extractHtmlFeed(Jsoup.parse(navigation, pageUrl), pageUrl))
    }

    @Test
    fun equallyPlausibleDisjointListsAreLeftUnselected() {
        val html = """
            <main>
                <section id="announcements">
                    <article><a href="/news/1">One</a></article>
                    <article><a href="/news/2">Two</a></article>
                </section>
                <section id="events">
                    <article><a href="/events/3">Three</a></article>
                    <article><a href="/events/4">Four</a></article>
                </section>
            </main>
        """.trimIndent()
        assertNull(extractHtmlFeed(Jsoup.parse(html, pageUrl), pageUrl))
    }

    @Test
    fun aMissingSavedListDoesNotSwitchToAnotherListOnTheSamePage() {
        val first = requireNotNull(extractHtmlFeed(fixture("news-cards"), pageUrl))
        val replacement = """
            <main><section class="latest">
                <article><a href="/other/1">Other 1</a></article>
                <article><a href="/other/2">Other 2</a></article>
            </section></main>
        """.trimIndent()
        assertNull(extractHtmlFeed(Jsoup.parse(replacement, pageUrl), pageUrl, requireNotNull(first.htmlItemSelector)))
    }

    private fun fixture(name: String): Document {
        val input = requireNotNull(javaClass.getResourceAsStream("/html-lists/$name.html"))
        return input.use { Jsoup.parse(it, "UTF-8", pageUrl) }
    }
}
