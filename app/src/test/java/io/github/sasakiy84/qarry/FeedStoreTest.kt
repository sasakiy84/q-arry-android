package io.github.sasakiy84.qarry

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FeedStoreTest {
    private lateinit var store: FeedStore
    private val example = FeedDocument(
        "https://publisher.example/feed",
        "Example feed",
        "https://publisher.example/",
        listOf(FeedArticle("first", "First article", "https://publisher.example/first", "Text", 1000L)),
    )

    @Before
    fun createStore() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("q-arry.db")
        store = FeedStore(context)
    }

    @After
    fun closeStore() {
        store.close()
    }

    @Test
    fun sameFeedAppearsInBothQueuesEvenAfterReopeningTheDatabase() {
        val feed = store.subscribe(example, null)
        assertTrue(store.queueName(null, "Research"))
        assertTrue(store.queueName(null, "Daily"))
        val queueIds = store.load(null, false, 10).queues.map { it.id }.toSet()
        store.setMembership(feed, queueIds)
        store.close()
        store = FeedStore(RuntimeEnvironment.getApplication())
        val all = store.load(null, false, 10)
        assertEquals(queueIds, all.subscriptions.single().queueIds)
        for (queue in all.queues) {
            assertEquals(1, queue.feedCount)
            assertEquals("First article", store.load(queue.id, false, 10).articles.single().title)
        }
        store.removeQueue(all.queues.first().id)
        assertEquals("First article", store.load(all.queues.last().id, false, 10).articles.single().title)
        assertEquals(1, store.subscriptions().size)
    }

    @Test
    fun updatingOneEntryPreservesItsReadStateAndDoesNotDuplicateTheSubscription() {
        val feed = store.subscribe(example, null)
        val article = store.load(null, false, 10).articles.single()
        store.setRead(article.id, true)
        val changed = example.copy(articles = example.articles.map { it.copy(title = "Corrected article") })
        assertEquals(feed, store.subscribe(changed, null))
        val state = store.load(null, false, 10)
        assertEquals(1, state.subscriptions.size)
        assertEquals("Corrected article", state.articles.single().title)
        assertTrue(state.articles.single().read)
        assertTrue(store.load(null, true, 10).articles.isEmpty())
    }

    @Test
    fun importingAnOpmlTwiceKeepsExistingArticlesAndFeedMetadata() = runBlocking {
        val original = example.copy(etag = "\"current\"")
        val existingId = store.subscribe(original, null)
        val library = FeedLibrary(store, FeedClient { error("Import does not require a network connection") })
        val input = """
            <opml version="2.0"><body>
              <outline text="Do not replace" xmlUrl="${original.url}"/>
              <outline text="新しいフィード" xmlUrl="https://other.example/atom"/>
            </body></opml>
        """.trimIndent()
        val first = library.importOpml { input.byteInputStream() }
        assertEquals(1, first.feedIds.size)
        assertEquals(1, first.duplicateCount)
        assertEquals(FeedImportResult(emptySet(), 2, 0), library.importOpml { input.byteInputStream() })

        val snapshot = store.load(null, false, 10)
        val existing = snapshot.subscriptions.single { it.id == existingId }
        assertEquals(original.title, existing.title)
        assertEquals(original.etag, existing.etag)
        val imported = snapshot.subscriptions.single { it.id == first.feedIds.single() }
        assertEquals("新しいフィード", imported.title)
        assertNull(imported.refreshedAt)
        assertEquals("First article", snapshot.articles.single().title)
    }

    @Test
    fun websiteRuleAndThumbnailSurviveReopeningWithOptionalFieldsMissing() {
        val site = example.copy(
            url = "https://publisher.example/news/",
            htmlItemSelector = "#news > article",
            articles = listOf(FeedArticle("five", null, "https://publisher.example/news/5", null, null,
                "https://publisher.example/images/5.jpg")),
        )
        store.subscribe(site, null)
        store.close()
        store = FeedStore(RuntimeEnvironment.getApplication())
        val snapshot = store.load(null, false, 10)
        assertEquals(site.htmlItemSelector, snapshot.subscriptions.single().htmlItemSelector)
        val article = snapshot.articles.single()
        assertEquals(site.articles.single().thumbnailUrl, article.thumbnailUrl)
        assertNull(article.title)
        assertNull(article.body)
        assertEquals(site.articles.single().url, article.displayTitle)
    }

    @Test
    fun aFavoriteFeedKeepsItsArticlesAndQueueAndRemainsFavoriteAfterRefresh() {
        assertTrue(store.queueName(null, "Research"))
        val queue = store.load(null, false, 10).queues.single()
        val feedId = store.subscribe(example, queue.id)
        val otherId = store.subscribe(example.copy(url = "https://other.example/feed"), null)
        store.setFeedFavorite(feedId, true)
        store.recordRefresh(feedId, example.copy(title = "Updated feed", home = null))
        store.close()
        store = FeedStore(RuntimeEnvironment.getApplication())

        val subscriptions = store.subscriptions().associateBy { it.id }
        val favorite = subscriptions.getValue(feedId)
        assertTrue(favorite.isFavorite)
        assertFalse(subscriptions.getValue(otherId).isFavorite)
        assertEquals(setOf(queue.id), favorite.queueIds)
        assertEquals(example.home, favorite.homeUrl)
        assertEquals("First article", store.feedArticles(feedId, 10).articles.single().title)

        store.setFeedFavorite(feedId, false)
        assertFalse(store.subscriptions().single { it.id == feedId }.isFavorite)
    }

    @Test
    fun sortsByNameNewestArticleOrMostRecentRegistration() {
        val older = example.copy(
            title = "Alpha",
            url = "https://alpha.example/feed",
            articles = example.articles.map { it.copy(published = 500L) },
        )
        val newerId = store.subscribe(example.copy(title = "Zeta"), null)
        val olderId = store.subscribe(older, null)
        val emptyId = store.subscribe(example.copy(title = "Beta", url = "https://beta.example/feed", articles = emptyList()), null)

        fun orderBy(order: FeedOrder): List<Long> = order.sort(store.subscriptions(), Locale.JAPANESE).map { it.id }

        assertEquals(listOf(olderId, emptyId, newerId), orderBy(FeedOrder.Name))
        assertEquals(listOf(newerId, olderId, emptyId), orderBy(FeedOrder.Updated))
        assertEquals(listOf(emptyId, olderId, newerId), orderBy(FeedOrder.Registered))

        store.recordRefresh(olderId, null)
        assertEquals(listOf(newerId, olderId, emptyId), orderBy(FeedOrder.Updated))

        store.recordRefresh(olderId, older.copy(articles = listOf(older.articles.single().copy(key = "undated", published = null))))
        assertEquals(listOf(olderId, newerId, emptyId), orderBy(FeedOrder.Updated))
    }

    @Test
    fun savingFeedbackRemovesAnArticleFromAllQueuesAndKeepsTheOriginalFeedbackAfterUnsubscribe() {
        val feedId = store.subscribe(example, null)
        assertTrue(store.queueName(null, "Research"))
        assertTrue(store.queueName(null, "Daily"))
        val queues = store.load(null, false, 10).queues
        store.setMembership(feedId, queues.map { it.id }.toSet())
        val original = store.load(null, false, 10).articles.single()
        store.recordView(original.id, 10L)
        store.dismissArticle(original.id, ArticleRating.Good)
        store.dismissArticle(original.id, ArticleRating.Bad)

        val changed = example.copy(articles = example.articles.map { it.copy(body = "Updated body") })
        store.recordRefresh(feedId, changed)
        assertTrue(store.load(null, false, 10).articles.isEmpty())
        queues.forEach { queue -> assertTrue(store.load(queue.id, false, 10).articles.isEmpty()) }
        assertTrue(store.feedArticles(feedId, 10).articles.isEmpty())
        val viewed = store.history(10).articles.single()
        assertEquals(original.id, viewed.id)
        assertEquals(ArticleRating.Good, viewed.rating)
        assertTrue(viewed.dismissed)

        store.removeFeed(feedId)
        val newFeedId = store.subscribe(changed, null)
        assertTrue(store.feedArticles(newFeedId, 10).articles.isEmpty())
        store.readableDatabase.rawQuery(
            "SELECT feed_url, entry_key, article_url, title, body, rating, rated_at FROM article_feedback",
            null,
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(example.url, rows.getString(0))
            assertEquals(example.articles.single().key, rows.getString(1))
            assertEquals(original.url, rows.getString(2))
            assertEquals(original.title, rows.getString(3))
            assertEquals(original.body, rows.getString(4))
            assertEquals(ArticleRating.Good.value, rows.getInt(5))
            assertTrue(rows.getLong(6) > 0)
            assertFalse(rows.moveToNext())
        }
    }

    @Test
    fun viewingRecordsTheMostRecentVisitAndManualRemovalDoesNotCreateFeedback() {
        val second = example.articles.single().copy(key = "second", title = "Second")
        val third = second.copy(key = "third", title = "Third")
        store.subscribe(example.copy(articles = example.articles + listOf(second, third)), null)
        val articles = store.load(null, false, 10).articles.associateBy { it.title }
        val firstId = articles.getValue("First article").id
        val secondId = articles.getValue("Second").id
        val thirdId = articles.getValue("Third").id
        store.recordView(firstId, 10L)
        store.recordView(secondId, 20L)
        store.recordView(firstId, 30L)
        store.setRead(firstId, false)
        store.dismissArticle(firstId, null)
        store.dismissArticle(thirdId, ArticleRating.Bad)

        val recent = store.history(1)
        assertTrue(recent.hasMore)
        assertEquals(firstId, recent.articles.single().id)
        assertNull(recent.articles.single().rating)
        assertFalse(recent.articles.single().read)
        assertEquals(listOf(firstId, secondId), store.history(10).articles.map { it.id })
        assertEquals(listOf(secondId), store.load(null, false, 10).articles.map { it.id })
        assertEquals(ArticleRating.Bad, store.article(thirdId)?.rating)
    }

    @Test
    fun ratingHistoryIncludesUnopenedArticlesAndAllowsChangingRatingsAfterUnsubscribe() {
        val feedId = store.subscribe(example, null)
        val original = store.load(null, false, 10).articles.single()
        store.dismissArticle(original.id, ArticleRating.Good)
        assertTrue(store.history(10).articles.isEmpty())
        val originalRating = store.ratingHistory(10).articles.single()
        assertEquals(original.id, originalRating.articleId)
        assertEquals(ArticleRating.Good, originalRating.rating)

        store.removeFeed(feedId)
        store.changeRating(originalRating.key, ArticleRating.Bad)
        val archivedRating = store.ratingHistory(10).articles.single()
        assertNull(archivedRating.articleId)
        assertEquals(original.displayTitle, archivedRating.title)
        assertEquals(original.url, archivedRating.url)
        assertEquals(originalRating.ratedAt, archivedRating.ratedAt)
        assertEquals(ArticleRating.Bad, archivedRating.rating)

        store.subscribe(example, null)
        assertTrue(store.load(null, false, 10).articles.isEmpty())
        val resubscribed = store.ratingHistory(10).articles.single()
        assertEquals(ArticleRating.Bad, store.article(checkNotNull(resubscribed.articleId))?.rating)
    }

    @Test
    fun clearingARecordedRatingRestoresTheArticleToAllQueues() {
        val deletedEntry = example.articles.single().copy(key = "deleted", title = "Removed manually")
        val feedId = store.subscribe(example.copy(articles = example.articles + deletedEntry), null)
        store.queueName(null, "Research")
        store.queueName(null, "Daily")
        val queues = store.load(null, false, 10).queues
        store.setMembership(feedId, queues.map { it.id }.toSet())
        val articles = store.load(null, false, 10).articles.associateBy { it.title }
        val rated = articles.getValue("First article")
        val manuallyRemoved = articles.getValue("Removed manually")
        store.dismissArticle(rated.id, ArticleRating.Bad)
        store.dismissArticle(manuallyRemoved.id, null)
        assertEquals(listOf(rated.id), store.ratingHistory(10).articles.map { it.articleId })

        val key = store.ratingHistory(10).articles.single().key
        store.changeRating(key, ArticleRating.Good)
        assertEquals(ArticleRating.Good, store.article(rated.id)?.rating)
        assertTrue(store.load(null, false, 10).articles.isEmpty())

        store.changeRating(key, null)
        store.changeRating(key, null)
        assertTrue(store.ratingHistory(10).articles.isEmpty())
        assertFalse(checkNotNull(store.article(rated.id)).dismissed)
        assertTrue(checkNotNull(store.article(manuallyRemoved.id)).dismissed)
        queues.forEach { queue -> assertEquals(rated.id, store.load(queue.id, false, 10).articles.single().id) }
    }

    @Test
    fun upgradingTheOriginalDatabaseRetainsExistingRssEntriesAndQueueMemberships() {
        assertTrue(store.queueName(null, "Research"))
        val queueId = store.load(null, false, 10).queues.single().id
        store.subscribe(example, queueId)
        val articleId = store.load(queueId, false, 10).articles.single().id
        store.setRead(articleId, true)
        // Rebuild the original schema without the later feed and article metadata.
        store.writableDatabase.apply {
            execSQL("DROP INDEX article_viewed")
            execSQL("DROP INDEX article_feed_newest")
            execSQL("DROP TABLE article_feedback")
            execSQL("ALTER TABLE feed DROP COLUMN html_item_selector")
            execSQL("ALTER TABLE feed DROP COLUMN home_url")
            execSQL("ALTER TABLE feed DROP COLUMN is_favorite")
            execSQL("ALTER TABLE article DROP COLUMN thumbnail_url")
            execSQL("ALTER TABLE article DROP COLUMN viewed_at")
            execSQL("ALTER TABLE article DROP COLUMN dismissed")
            version = 1
        }
        store.close()
        store = FeedStore(RuntimeEnvironment.getApplication())
        val migrated = store.load(queueId, false, 10)
        assertNull(migrated.subscriptions.single().htmlItemSelector)
        assertEquals(setOf(queueId), migrated.subscriptions.single().queueIds)
        val article = migrated.articles.single()
        assertEquals("First article", article.title)
        assertTrue(article.read)
        assertNull(article.thumbnailUrl)
        assertFalse(article.dismissed)
        assertTrue(store.history(10).articles.isEmpty())
    }

    @Test
    fun upgradingVersionTwoRetainsTheWebsiteRuleAndThumbnail() {
        val website = example.copy(
            htmlItemSelector = "main article",
            etag = "\"legacy\"",
            articles = example.articles.map { it.copy(thumbnailUrl = "https://publisher.example/image.png") },
        )
        val feedId = store.subscribe(website, null)
        store.writableDatabase.apply {
            execSQL("DROP INDEX article_viewed")
            execSQL("DROP INDEX article_feed_newest")
            execSQL("DROP TABLE article_feedback")
            execSQL("ALTER TABLE feed DROP COLUMN home_url")
            execSQL("ALTER TABLE feed DROP COLUMN is_favorite")
            execSQL("ALTER TABLE article DROP COLUMN viewed_at")
            execSQL("ALTER TABLE article DROP COLUMN dismissed")
            version = 2
        }
        store.close()
        store = FeedStore(RuntimeEnvironment.getApplication())
        val subscription = store.subscriptions().single()
        assertEquals(website.htmlItemSelector, subscription.htmlItemSelector)
        assertFalse(subscription.isFavorite)
        assertNull(subscription.etag)
        val article = store.feedArticles(feedId, 10).articles.single()
        assertEquals(website.articles.single().thumbnailUrl, article.thumbnailUrl)
        assertFalse(article.dismissed)
    }

    @Test
    fun upgradingVersionThreeKeepsExistingFeedLinksAndHistory() {
        val feedId = store.subscribe(example, null)
        val articleId = store.feedArticles(feedId, 10).articles.single().id
        store.recordView(articleId)
        store.writableDatabase.apply {
            execSQL("DROP INDEX article_feed_newest")
            execSQL("ALTER TABLE feed DROP COLUMN is_favorite")
            version = 3
        }
        store.close()
        store = FeedStore(RuntimeEnvironment.getApplication())

        val feed = store.subscriptions().single()
        assertEquals(example.home, feed.homeUrl)
        assertFalse(feed.isFavorite)
        assertEquals(articleId, store.history(10).articles.single().id)
    }

    @Test
    fun upgradingVersionFourKeepsFavoritesAndComputesTheLatestArticleDate() {
        val feedId = store.subscribe(example, null)
        store.setFeedFavorite(feedId, true)
        store.writableDatabase.apply {
            execSQL("DROP INDEX article_feed_newest")
            version = 4
        }
        store.close()
        store = FeedStore(RuntimeEnvironment.getApplication())

        val restored = store.subscriptions().single()
        assertTrue(restored.isFavorite)
        assertEquals(example.articles.single().published, restored.latestArticleAt)
    }
}
