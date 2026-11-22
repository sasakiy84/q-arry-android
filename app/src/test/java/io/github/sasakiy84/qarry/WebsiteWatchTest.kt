package io.github.sasakiy84.qarry

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class WebsiteWatchTest {
    private val url = "https://example.com/training"
    private val science = table(1, 2)
    private val history = table(3, 4)

    @Test
    fun aTableStaysWithItsHeadingWhenAPrecedingNoteAndANewRowAreAdded() {
        val selected = scopes(page(science)).options.single { it.label == "科学技術" }.feed
        val changed = page("<p>補足説明</p>${table(1, 2, 5)}")

        assertEquals(setOf("$url/1", "$url/2", "$url/5"), refresh(changed, selected)?.articles?.map { it.url }?.toSet())
        assertEquals("science", selected.htmlWatch?.let(::decodeWebsiteWatch)?.heading?.id)
    }

    @Test
    fun rejectsAnUnrelatedTableAtTheOldPositionAndAllowsChoosingItInPreview() {
        val selected = scopes(page(science)).options.single { it.label == "科学技術" }.feed
        val otherTable = table(5, 6)
        val inserted = page(otherTable + science)

        assertNull(refresh(inserted, selected))
        assertNull(refresh(page(otherTable), selected))
        val chosen = scopes(inserted).options.single { it.feed.articles.map { article -> article.url } == listOf("$url/5", "$url/6") }.feed
        assertEquals(chosen.articles, refresh(inserted, chosen)?.articles)
        assertNotEquals(selected.htmlWatch, chosen.htmlWatch)
    }

    @Test
    fun positionalRulesFromEarlierVersionsCannotSwitchToAnUnseenTable() {
        val selected = scopes(page(science)).options.single { it.label == "科学技術" }.feed
        val selector = requireNotNull(selected.htmlItemSelector)
        val previousKeys = selected.articles.map { it.key }.toSet()

        assertTrue(selector.contains(":nth-child("))
        assertNotNull(refreshWebsiteDocument(Jsoup.parse(page(science), url), url, selector, null, previousKeys)?.htmlWatch)
        val withNote = Jsoup.parse(page("<p>補足説明</p>$science"), url)
        val recovered = requireNotNull(refreshWebsiteDocument(withNote, url, selector, null, previousKeys))
        assertEquals(selected.articles, recovered.articles)
        assertEquals("science", recovered.htmlWatch?.let(::decodeWebsiteWatch)?.heading?.id)
        assertNull(refreshWebsiteDocument(Jsoup.parse(page(table(5, 6)), url), url, selector, null, previousKeys))
    }

    @Test
    fun anIdentifiableEmptyTableRetainsThePreviousArticleFingerprint() {
        val selected = scopes(page(science)).options.single { it.label == "科学技術" }.feed
        val empty = requireNotNull(refresh(page(table()), selected))
        assertTrue(empty.articles.isEmpty())
        assertEquals(selected.htmlWatch, empty.htmlWatch)
        assertEquals(selected.articles, refresh(page(science), empty)?.articles)
        assertNull(refresh(page(table(5, 6)), empty))
    }

    @Test
    fun equivalentSelectorsPreferSemanticClassesAndSurviveLayoutWrappers() {
        val cards = """
            <article class="tile-a7 news-card"><h2><a href="/news/1">1</a></h2></article>
            <article class="tile-a7 news-card"><h2><a href="/news/2">2</a></h2></article>
        """.trimIndent()
        val page = "<main id='news'>$cards</main>"
        val selected = scopes(page).options.first().feed
        assertEquals("#news article.news-card", selected.htmlItemSelector)

        val changed = Jsoup.parse(page.replace("tile-a7", "tile-b8"), url)
        for (item in changed.select("article")) item.wrap("<div class='layout'></div>")
        assertEquals(selected.articles, refresh(changed.outerHtml(), selected)?.articles)
        changed.selectFirst("#news")!!.append(
            "<article class='news-card'><h2><a href='/campaign/3'>キャンペーン</a></h2></article>",
        )
        assertNull(refresh(changed.outerHtml(), selected))
    }

    @Test
    fun firstItemModeSkipsAnInsertedNoteAndFollowsANewFirstArticle() {
        val selected = scopes("""
            <main id="news">
              <article><a href="/news/2">2</a></article><article><a href="/news/1">1</a></article>
            </main>
        """.trimIndent()).options.single { it.label == "先頭の1件" }.feed
        val changed = """
            <main id="news">
              <p>補足説明</p><article><a href="/news/3">3</a></article><article><a href="/news/2">2</a></article>
            </main>
        """.trimIndent()
        assertEquals(listOf("https://example.com/news/3"), refresh(changed, selected)?.articles?.map { it.url })
    }

    private fun refresh(html: String, feed: FeedDocument): FeedDocument? =
        refreshWebsiteDocument(Jsoup.parse(html, url), url, requireNotNull(feed.htmlItemSelector), feed.htmlWatch)

    private fun scopes(html: String): WebsiteScopes = requireNotNull(websiteScopes(Jsoup.parse(html, url), url, null, null))

    private fun page(scienceSection: String): String =
        "<main id='training'><h2 id='science'>科学技術</h2>$scienceSection<h2 id='history'>歴史</h2>$history</main>"

    private fun table(vararg ids: Int): String = """
        <figure class="course-table"><table>
        <thead><tr><th>講座</th><th>概要</th></tr></thead>
        <tbody>${ids.joinToString("") { "<tr><td><a href='/training/$it'>講座 $it</a></td><td>概要 $it</td></tr>" }}</tbody>
        </table></figure>
    """.trimIndent()
}
