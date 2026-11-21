package io.github.sasakiy84.qarry

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

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
    fun theSavedSelectorStillWorksWhenTheListShrinksToOneArticle() {
        val first = requireNotNull(extractHtmlFeed(fixture("news-cards"), pageUrl))
        val updated = requireNotNull(
            extractHtmlFeed(fixture("news-single-after-refresh"), pageUrl, requireNotNull(first.htmlItemSelector)),
        )
        assertEquals("https://museum.example/archive/news/45", updated.articles.single().url)
        assertEquals(first.htmlItemSelector, updated.htmlItemSelector)
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
