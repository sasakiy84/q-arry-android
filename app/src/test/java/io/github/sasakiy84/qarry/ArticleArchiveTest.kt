package io.github.sasakiy84.qarry

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArticleArchiveTest {
    @Test
    fun automaticArchiveIndexesOneBatchAndSearchCanFinishTheBacklog() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("q-arry.db")
        FeedStore(context).use { store ->
            val client = FeedClient { error("These entries have no downloadable article URLs") }
            val archive = ArticleArchive(store, client, null)
            store.subscribe(
                FeedDocument("https://publisher.example/feed", "研究", null, (1..55).map {
                    FeedArticle("entry-$it", "探査報告 $it", null, null, null)
                }), null,
            )

            assertEquals(0, archive.fetchPending(completeLexicalIndex = false))
            assertEquals(40L, archive.stats().indexed)
            assertEquals(55, archive.search("探査", 60).listing.articles.size)
            assertEquals(55L, archive.stats().indexed)
        }
    }

    @Test
    fun archivesTheBodyAndSearchesJapaneseEvenAfterTheArticleLeavesTheQueues() = runBlocking {
        val url = "https://publisher.example/article"
        val html = """
            <nav><a href="/menu">共通ナビゲーション</a></nav>
            <main><article><h1>地域の図書館を訪ねて</h1>
            <p>この地域に残る貴重な資料を分析しました。研究者は歴史資料を電子化し、図書館の利用者がいつでも読めるようにしています。</p>
            <p>紀要の続報にはＣＳＳの更新情報と、紙資料の保存技術についての詳しい解説を掲載します。</p>
            <div class="post-related"><p>広告キャンペーンのお知らせ</p></div>
            </article></main>
            <footer><p>運営会社についてのお問い合わせ</p></footer>
        """.trimIndent()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("q-arry.db")
        FeedStore(context).use { store ->
            val archive = ArticleArchive(store, fixtureWebsiteClient(url) { html }, null)
            val document = FeedDocument("https://publisher.example/feed", "配信元", null, listOf(
                FeedArticle("one", "地域の図書館を訪ねて", url, "記事の短い要約", null),
            ))
            val feed = store.subscribe(document, null)
            val id = store.feedArticles(feed, 1).articles.single().id
            assertFalse(store.semanticEnabled())
            assertEquals(listOf(id), archive.search("図書", 10).listing.articles.map { it.id })
            assertTrue(archive.search("電子化", 10).listing.articles.isEmpty())
            store.dismissArticle(id, null)
            assertEquals(1, archive.fetchPending())
            assertEquals(0, archive.fetchPending())

            val saved = checkNotNull(store.article(id)?.archivedBody)
            assertTrue("\n\n" in saved && "資料を電子化" in saved)
            assertFalse("運営会社" in saved || "広告キャンペーン" in saved || "共通ナビゲーション" in saved)
            for (query in listOf("資料を電子化", "保存技術 Ｃｓｓ", "館")) {
                val matches = archive.search(query, 10).listing.articles
                assertEquals(query, listOf(id), matches.map { it.id })
                assertTrue(matches.single().dismissed)
            }
            assertTrue(archive.search("キャンペーン", 10).listing.articles.isEmpty())
            val stats = archive.stats()
            assertEquals(1L, stats.indexed)
            assertTrue(stats.archiveBytes > 0 && checkNotNull(stats.lexicalBytes) > 0)
            assertEquals(0L, stats.vectors)

            store.subscribe(document.copy(articles = document.articles.map { it.copy(url = "$url/moved") }), null)
            assertNull(store.article(id)?.archivedBody)
            assertTrue(archive.search("保存技術", 10).listing.articles.isEmpty())
            store.removeFeed(feed)
            assertEquals(0L, archive.stats().indexed)
        }
    }

    @Test
    fun avoidsNavigationAndRespectsNoarchiveAndFusesVectorRanks() {
        val links = "<main>" + (1..16).joinToString("") { "<p><a href='/entry/$it'>ニュースの概要を表示します $it</a></p>" } + "</main>"
        assertNull(extractArticleText(Jsoup.parse(links)))
        assertNull(extractArticleText(Jsoup.parse("<meta name=robots content='index, noarchive'><article><p>${"本文です。".repeat(30)}</p></article>")))
        val robots = ArchiveRobots.parse("User-agent: *\nDisallow: /members/*\nAllow: /members/open$")
        assertFalse(robots.allows("/members/read"))
        assertFalse(robots.allows("/members/open?preview=1"))
        assertTrue(robots.allows("/members/open"))
        assertEquals(listOf(2L, 1L, 3L), hybridArticleOrder(listOf(1L, 2L), listOf(3L, 2L)))
        assertEquals(1f, embeddingSimilarity(floatArrayOf(1f, 0f), encodeEmbedding(floatArrayOf(1f, 0f))), 0.001f)
    }
}
