package io.github.sasakiy84.qarry

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.time.Instant
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
    fun exportingAndImportingRestoresRssAndWebsiteSubscriptionsIncludingTheSavedSelector() = runBlocking {
        val rssId = store.subscribe(example, null)
        store.queueName(null, "研究")
        store.queueName(null, "資料/確認, A & B")
        store.queueName(null, "空のキュー")
        val queues = store.load(null, false, 10).queues
        val (research, materials) = queues
        val feedFilter = KeywordFilter(KeywordFilterMode.Include, listOf("論文", "調査"))
        val queueFilter = KeywordFilter(KeywordFilterMode.Exclude, listOf("広告"))
        store.setMembership(rssId, setOf(research.id, materials.id))
        store.setFeedFavorite(rssId, true)
        store.setFeedNotifications(rssId, FeedNotificationMode.On)
        store.setFeedKeywordFilter(rssId, feedFilter)
        val article = store.feedArticles(rssId, 10, applyKeywordFilter = false).articles.single()
        val archivedBody = "エクスポート対象外の保存済み本文"
        store.saveArchive(ArchiveCandidate(article.id, checkNotNull(article.url)), archivedBody, Long.MAX_VALUE)
        val site = example.copy(
            url = "https://publisher.example/news/",
            title = "更新のお知らせ",
            home = "https://publisher.example/news/",
            htmlItemSelector = "#news > article:nth-child(2)",
            htmlWatch = encodeWebsiteWatch(WebsiteWatch(null, null, false, setOf("publisher.example/news/"), setOf("entry"))),
        )
        val siteId = store.subscribe(site, null)
        store.setFeedNotifications(siteId, FeedNotificationMode.Off)
        store.setMembership(siteId, setOf(research.id), mapOf(research.id to queueFilter))
        val library = FeedLibrary(store, FeedClient { error("OPML file operations don't require a network connection") })
        val output = ByteArrayOutputStream()
        assertEquals(2, library.exportOpml { output })
        assertFalse(archivedBody in output.toString(Charsets.UTF_8))
        assertFalse("First article" in output.toString(Charsets.UTF_8))

        store.removeFeed(rssId)
        store.removeFeed(siteId)
        queues.forEach { store.removeQueue(it.id) }
        val imported = library.importOpml { output.toByteArray().inputStream() }
        assertEquals(2, imported.feedIds.size)
        assertEquals(0, imported.unsupportedCount)
        assertEquals(3, imported.createdQueues)
        val restored = store.subscriptions().associateBy { it.url }
        val restoredQueues = store.load(null, false, 10).queues.associate { it.name to it.id }
        assertEquals(queues.map { it.name }, restoredQueues.keys.toList())
        val researchId = restoredQueues.getValue(research.name)
        assertNull(restored.getValue(example.url).htmlItemSelector)
        assertEquals(example.home, restored.getValue(example.url).homeUrl)
        assertTrue(restored.getValue(example.url).isFavorite)
        assertEquals(FeedNotificationMode.On, restored.getValue(example.url).notifications)
        assertEquals(feedFilter, restored.getValue(example.url).keywordFilter)
        assertEquals(setOf(researchId, restoredQueues.getValue(materials.name)), restored.getValue(example.url).queueIds)
        assertEquals(site.title, restored.getValue(site.url).title)
        assertEquals(site.htmlItemSelector, restored.getValue(site.url).htmlItemSelector)
        assertEquals(site.htmlWatch, restored.getValue(site.url).htmlWatch)
        assertEquals(FeedNotificationMode.Off, restored.getValue(site.url).notifications)
        assertEquals(mapOf(researchId to queueFilter), restored.getValue(site.url).queueKeywordFilters)

        store.setFeedFavorite(restored.getValue(example.url).id, false)
        assertEquals(FeedImportResult(emptySet(), 2, 0), library.importOpml { output.toByteArray().inputStream() })
        assertFalse(store.subscriptions().first { it.url == example.url }.isFavorite)
    }

    @Test
    fun exportsEveryCurrentRatingIncludingUnsubscribedArticlesWithoutBodyText() = runBlocking {
        val document = example.copy(articles = listOf(
            FeedArticle("entry-1", "研究の「見どころ」", "https://publisher.example/first", "出力しない本文", 1000L),
            FeedArticle("entry-2", "次の記事", null, "出力しない本文", 2000L),
        ))
        val feed = store.subscribe(document, null)
        val articles = store.feedArticles(feed, 10).articles.associateBy { it.url }
        store.dismissArticle(articles.getValue("https://publisher.example/first").id, ArticleRating.Good)
        store.dismissArticle(articles.getValue(null).id, ArticleRating.Bad)
        val first = store.ratingHistory(10).articles.single { it.key.entryKey == "entry-1" }
        store.changeRating(first.key, ArticleRating.Bad)
        store.removeFeed(feed)
        assertTrue(store.ratingHistory(1).hasMore)

        val output = ByteArrayOutputStream()
        val library = FeedLibrary(store, FeedClient { error("Export should be offline") })
        assertEquals(2, library.exportRatingHistory { output })
        val json = output.toString(Charsets.UTF_8)
        val ratings = JSONArray(json)
        assertEquals(2, ratings.length())
        assertFalse("出力しない本文" in json)
        val sameEntry = (0 until ratings.length()).map(ratings::getJSONObject).single { it.getString("entryKey") == "entry-1" }
        assertEquals("研究の「見どころ」", sameEntry.getString("title"))
        assertEquals(example.url, sameEntry.getString("feedUrl"))
        assertEquals("https://publisher.example/first", sameEntry.getString("articleUrl"))
        assertEquals("Bad", sameEntry.getString("rating"))
        assertEquals(first.ratedAt, Instant.parse(sameEntry.getString("ratedAt")).toEpochMilli())
        assertTrue((0 until ratings.length()).map(ratings::getJSONObject).single { it.getString("entryKey") == "entry-2" }.isNull("articleUrl"))
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
    fun recordsOriginalOpensAndEachRemovalSourceWithoutRemovingSubscriptions() {
        store.queueName(null, "Research")
        store.queueName(null, "Daily")
        val (research, daily) = store.load(null, false, 1).queues
        val entries = listOf("single", "open", "queue1", "queue2", "read", "excluded").mapIndexed { index, key ->
            val title = if (key == "excluded") key else "対象 $key"
            FeedArticle(key, title, "https://publisher.example/$key", null, 1000L + index)
        }
        val feedId = store.subscribe(example.copy(articles = entries), null)
        val otherFeed = store.subscribe(example.copy(url = "https://other.example/feed"), daily.id)
        val include = KeywordFilter(KeywordFilterMode.Include, listOf("対象"))
        store.setMembership(feedId, setOf(research.id, daily.id), mapOf(research.id to include))
        val ids = store.feedArticles(feedId, 10).articles.associate { it.url!!.substringAfterLast('/') to it.id }
        store.setRead(ids.getValue("read"), true)
        store.dismissArticle(ids.getValue("single"), null, research.id)
        store.dismissArticle(ids.getValue("single"), null, research.id)

        val opened = ids.getValue("open")
        store.recordOriginalOpen(opened, research.id, openedAt = 5000L)
        store.recordOriginalOpen(opened, openedAt = 6000L)
        val viewed = store.history(10).articles.single()
        assertEquals(6000L, viewed.originalOpenedAt)
        assertEquals(6000L, viewed.viewedAt)
        assertTrue(viewed.read && viewed.dismissed)
        store.dismissArticle(opened, ArticleRating.Good)
        val rating = store.ratingHistory(10).articles.single()
        assertEquals(ArticleRating.Good, rating.rating)
        store.changeRating(rating.key, null)
        assertTrue(checkNotNull(store.article(opened)).dismissed)

        assertTrue(store.load(research.id, true, 1).hasMore)
        assertEquals(2, store.dismissQueueArticles(research.id, unreadOnly = true))
        assertEquals(0, store.dismissQueueArticles(research.id, unreadOnly = true))
        assertEquals(setOf("対象 read", "excluded"), store.feedArticles(feedId, 10).articles.mapNotNull { it.title }.toSet())
        store.setFeedKeywordFilter(feedId, include)
        assertEquals(2, store.dismissFeedArticles(feedId))
        assertEquals(0, store.dismissFeedArticles(feedId))
        assertEquals(otherFeed, store.load(daily.id, false, 10).articles.single().feedId)
        assertEquals(setOf(research.id, daily.id), store.subscriptions().first { it.id == feedId }.queueIds)

        store.removeFeed(feedId)
        val actions = mutableListOf<Triple<String, String, Long?>>()
        store.readableDatabase.rawQuery(
            "SELECT entry_key, kind, queue_id FROM article_action WHERE feed_url = ? ORDER BY id",
            arrayOf(example.url),
        ).use { rows ->
            while (rows.moveToNext()) {
                actions.add(Triple(rows.getString(0), rows.getString(1), if (rows.isNull(2)) null else rows.getLong(2)))
            }
        }
        val expected = listOf(
            Triple("single", ArticleAction.RemovedIndividually.name, research.id),
            Triple("open", ArticleAction.OriginalOpened.name, research.id),
            Triple("open", ArticleAction.OriginalOpened.name, null),
            Triple("queue1", ArticleAction.RemovedFromQueueInBulk.name, research.id),
            Triple("queue2", ArticleAction.RemovedFromQueueInBulk.name, research.id),
            Triple("read", ArticleAction.RemovedFromFeedInBulk.name, null),
            Triple("excluded", ArticleAction.RemovedFromFeedInBulk.name, null),
        )
        assertEquals(expected.groupingBy { it }.eachCount(), actions.groupingBy { it }.eachCount())
    }

    @Test
    fun feedFilterMatchesTheOriginalTitleOrDescriptionBeforePagination() {
        val feedId = store.subscribe(example.copy(articles = listOf(
            FeedArticle("latest", "キーワードなし", null, "別の記事", 4000),
            FeedArticle("keyword-in-description", "刊行案内", null, "概要 ".repeat(120) + "図書館", 3000),
            FeedArticle("keyword-in-title", "Review 100%", null, null, 2000),
            FeedArticle("ordinary", "Review 100冊", null, null, 1000),
        )), null)
        store.queueName(null, "Daily")
        val queueId = store.load(null, false, 10).queues.single().id
        store.setMembership(feedId, setOf(queueId))
        val latestId = store.feedArticles(feedId, 10).articles.first().id
        store.recordView(latestId)

        store.setFeedKeywordFilter(feedId, KeywordFilter(KeywordFilterMode.Include, listOf("図書館", "review 100%")))

        val listing = store.feedArticles(feedId, 1)
        assertEquals(listOf("刊行案内"), listing.articles.map { it.title })
        assertTrue(listing.hasMore)
        val expected = listOf("刊行案内", "Review 100%")
        assertEquals(expected, store.load(null, false, 10).articles.map { it.title })
        assertEquals(expected, store.load(queueId, false, 10).articles.map { it.title })
        assertEquals(4, store.feedArticles(feedId, 10, applyKeywordFilter = false).articles.size)
        assertEquals(latestId, store.history(10).articles.single().id)
        store.setFeedKeywordFilter(feedId, null)
        assertEquals(4, store.load(queueId, false, 10).articles.size)
    }

    @Test
    fun markAllReadUsesTheSelectedArticleListAcrossPages() {
        store.queueName(null, "Research")
        val queueId = store.load(null, false, 1).queues.single().id
        val feedId = store.subscribe(example.copy(articles = listOf(
            FeedArticle("first", "図書館 1", null, null, 4000),
            FeedArticle("second", "図書館 2", null, null, 3000),
            FeedArticle("excluded", "その他", null, null, 2000),
            FeedArticle("dismissed", "図書館 3", null, null, 1000),
        )), null)
        store.subscribe(example.copy(url = "https://other.example/feed"), null)
        val dismissed = store.feedArticles(feedId, 10).articles.last()
        store.dismissArticle(dismissed.id, ArticleRating.Bad)
        val include = KeywordFilter(KeywordFilterMode.Include, listOf("図書館"))
        store.setMembership(feedId, setOf(queueId), mapOf(queueId to include))

        assertTrue(store.load(queueId, true, 1).hasMore)
        assertEquals(2, store.markAllRead(queueId))
        assertTrue(store.load(queueId, true, 10).articles.isEmpty())
        assertFalse(store.load(queueId, false, 1).hasUnread)
        assertEquals(listOf("その他", "First article"), store.load(null, true, 10).articles.map { it.title })
        assertEquals(0, store.markAllRead(queueId))
        assertEquals(listOf("First article"), store.load(null, true, 1, unassignedOnly = true).articles.map { it.title })
        assertEquals(1, store.markAllRead(null, unassignedOnly = true))
        assertFalse(store.load(null, false, 1, unassignedOnly = true).hasUnread)
        assertEquals(listOf("その他"), store.load(null, true, 10).articles.map { it.title })
        assertEquals(1, store.markAllRead(null))
        assertTrue(store.load(null, true, 10).articles.isEmpty())
        assertFalse(checkNotNull(store.article(dismissed.id)).read)
        assertTrue(store.history(10).articles.isEmpty())
    }

    @Test
    fun keywordFiltersApplyOnlyToTheSelectedFeedAndQueuePair() {
        val feedId = store.subscribe(example.copy(articles = listOf(
            FeedArticle("news", "図書館のニュース", null, null, 3000),
            FeedArticle("review", "書評", null, null, 2000),
        )), null)
        store.queueName(null, "Research")
        store.queueName(null, "Daily")
        val (research, daily) = store.load(null, false, 10).queues.map { it.id }
        store.subscribe(example.copy(url = "https://other.example/feed"), research)
        val researchFilter = KeywordFilter(KeywordFilterMode.Include, listOf("書評"))
        val dailyFilter = KeywordFilter(KeywordFilterMode.Exclude, listOf("書評"))
        store.setMembership(feedId, setOf(research, daily), mapOf(research to researchFilter, daily to dailyFilter))

        assertEquals(listOf("書評", "First article"), store.load(research, false, 10).articles.map { it.title })
        assertEquals(listOf("図書館のニュース"), store.load(daily, false, 10).articles.map { it.title })
        assertEquals(3, store.load(null, false, 10).articles.size)
        assertEquals(2, store.feedArticles(feedId, 10).articles.size)

        store.setMembership(feedId, setOf(research))
        store.close()
        store = FeedStore(RuntimeEnvironment.getApplication())
        assertEquals(mapOf(research to researchFilter), store.subscriptions().single { it.id == feedId }.queueKeywordFilters)
        store.removeQueue(research)
        assertTrue(store.subscriptions().single { it.id == feedId }.queueKeywordFilters.isEmpty())
    }

    @Test
    fun savingAFilterScopeReplacesTheOtherScopeWithoutChangingMembershipsOrArticles() {
        val feedId = store.subscribe(example, null)
        store.queueName(null, "Research")
        val queueId = store.load(null, false, 10).queues.single().id
        val rule = KeywordFilter(KeywordFilterMode.Exclude, listOf("First"))
        store.setMembership(feedId, setOf(queueId), mapOf(queueId to rule))

        store.setFeedKeywordFilter(feedId, rule)
        var feed = store.subscriptions().single()
        assertEquals(rule, feed.keywordFilter)
        assertTrue(feed.queueKeywordFilters.isEmpty())
        assertEquals(setOf(queueId), feed.queueIds)
        assertTrue(store.load(null, false, 10).articles.isEmpty())

        store.setMembership(feedId, setOf(queueId), emptyMap())
        assertEquals(rule, store.subscriptions().single().keywordFilter)
        store.setMembership(feedId, setOf(queueId), mapOf(queueId to rule))
        feed = store.subscriptions().single()
        assertNull(feed.keywordFilter)
        assertEquals(mapOf(queueId to rule), feed.queueKeywordFilters)
        assertTrue(store.load(queueId, false, 10).articles.isEmpty())

        store.setMembership(feedId, setOf(queueId), emptyMap())
        assertEquals("First article", store.load(queueId, false, 10).articles.single().title)
    }

    @Test
    fun upgradingTheOriginalDatabaseRetainsExistingRssEntriesAndQueueMemberships() {
        assertTrue(store.queueName(null, "Research"))
        val queueId = store.load(null, false, 10).queues.single().id
        store.subscribe(example, queueId)
        val articleId = store.load(queueId, false, 10).articles.single().id
        store.setRead(articleId, true)
        // Rebuild the original schema without the later feed and article metadata.
        reopenFromVersion(1) {
            execSQL("DROP TABLE keyword_filter")
            execSQL("DROP INDEX article_viewed")
            execSQL("DROP INDEX article_feed_newest")
            execSQL("DROP TABLE article_feedback")
            execSQL("ALTER TABLE feed DROP COLUMN html_item_selector")
            execSQL("ALTER TABLE feed DROP COLUMN home_url")
            execSQL("ALTER TABLE feed DROP COLUMN is_favorite")
            execSQL("ALTER TABLE article DROP COLUMN thumbnail_url")
            execSQL("ALTER TABLE article DROP COLUMN viewed_at")
            execSQL("ALTER TABLE article DROP COLUMN dismissed")
        }
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
        reopenFromVersion(2) {
            execSQL("DROP TABLE keyword_filter")
            execSQL("DROP INDEX article_viewed")
            execSQL("DROP INDEX article_feed_newest")
            execSQL("DROP TABLE article_feedback")
            execSQL("ALTER TABLE feed DROP COLUMN home_url")
            execSQL("ALTER TABLE feed DROP COLUMN is_favorite")
            execSQL("ALTER TABLE article DROP COLUMN viewed_at")
            execSQL("ALTER TABLE article DROP COLUMN dismissed")
        }
        val subscription = store.subscriptions().single()
        assertEquals(website.htmlItemSelector, subscription.htmlItemSelector)
        assertFalse(subscription.isFavorite)
        assertNull(subscription.etag)
        val article = store.feedArticles(feedId, 10).articles.single()
        assertEquals(website.articles.single().thumbnailUrl, article.thumbnailUrl)
        assertFalse(article.dismissed)
    }

    @Test
    fun upgradingVersionFiveKeepsFeedDetailsHistoryAndRatingsWithoutCreatingFilters() {
        store.queueName(null, "Daily")
        val queueId = store.load(null, false, 10).queues.single().id
        val feedId = store.subscribe(example, queueId)
        val articleId = store.load(queueId, false, 10).articles.single().id
        store.recordView(articleId)
        store.setFeedFavorite(feedId, true)
        store.dismissArticle(articleId, ArticleRating.Good)
        reopenFromVersion(5) {
            execSQL("DROP TABLE keyword_filter")
        }

        val restored = store.subscriptions().single()
        assertEquals(feedId, restored.id)
        assertEquals(example.home, restored.homeUrl)
        assertEquals(example.articles.single().published, restored.latestArticleAt)
        assertTrue(restored.isFavorite)
        assertEquals(articleId, store.history(10).articles.single().id)
        assertEquals(setOf(queueId), restored.queueIds)
        assertNull(restored.keywordFilter)
        assertTrue(restored.queueKeywordFilters.isEmpty())
        assertEquals(ArticleRating.Good, store.article(articleId)?.rating)
    }

    @Test
    fun reselectingAMigratedWebsiteRetainsArticlesAndRejectsAnOlderRefresh() {
        store.queueName(null, "Daily")
        val queueId = store.load(null, false, 10).queues.single().id
        val website = example.copy(htmlItemSelector = "main > table:nth-child(2) > tbody > tr")
        val feedId = store.subscribe(website, queueId)
        val rule = KeywordFilter(KeywordFilterMode.Include, listOf("article"))
        store.setFeedKeywordFilter(feedId, rule)
        val oldArticle = store.load(queueId, false, 10).articles.single()
        store.recordView(oldArticle.id)
        store.setFeedFavorite(feedId, true)
        reopenFromVersion(6)
        val migrated = store.subscriptions().single()
        assertEquals(website.htmlItemSelector, migrated.htmlItemSelector)
        assertNull(migrated.htmlWatch)
        assertEquals(rule, migrated.keywordFilter)

        val choice = website.copy(
            htmlItemSelector = "#updates article",
            htmlWatch = encodeWebsiteWatch(WebsiteWatch(null, null, false, setOf("publisher.example/news/"), emptySet())),
            articles = listOf(FeedArticle("second", "Second article", "https://publisher.example/news/2", null, 2000L)),
        )
        assertTrue(store.recordRefresh(feedId, null, expected = migrated, error = WEBSITE_SCOPE_CHANGED).applied)
        assertEquals(WEBSITE_SCOPE_CHANGED, store.subscriptions().single().error)
        assertTrue(store.updateWebsiteScope(feedId, choice))
        assertFalse(store.recordRefresh(feedId, website, expected = migrated).applied)
        assertFalse(store.recordRefresh(feedId, null, expected = migrated, error = WEBSITE_SCOPE_CHANGED).applied)
        val updated = store.subscriptions().single()
        assertEquals(choice.htmlItemSelector, updated.htmlItemSelector)
        assertEquals(choice.htmlWatch, updated.htmlWatch)
        assertNull(updated.error)
        assertEquals(setOf(queueId), updated.queueIds)
        assertEquals(rule, updated.keywordFilter)
        assertTrue(updated.isFavorite)
        assertEquals(listOf("Second article", "First article"), store.feedArticles(feedId, 10).articles.map { it.title })
        assertTrue(checkNotNull(store.article(oldArticle.id)).read)
        assertEquals(oldArticle.id, store.history(10).articles.single().id)
    }

    @Test
    fun undatedArticlesKeepTheDetectionTimeUntilTheirContentChanges() {
        val initial = example.copy(
            observedAt = 1_000L,
            articles = example.articles.map { it.copy(published = null, publishedTime = null) },
        )
        val feedId = store.subscribe(initial, null)
        val articleId = store.feedArticles(feedId, 10).articles.single().id
        assertEquals(1_000L, store.article(articleId)?.discoveredAt)
        assertNull(store.article(articleId)?.changeDetectedAt)

        store.recordRefresh(feedId, initial.copy(observedAt = 2_000L))
        assertEquals(1_000L, store.article(articleId)?.discoveredAt)
        assertNull(store.article(articleId)?.changeDetectedAt)

        val changeTime = 120_000L
        val changed = initial.copy(observedAt = changeTime, articles = initial.articles.map { it.copy(body = "更新") })
        store.recordRefresh(feedId, changed)
        store.recordRefresh(feedId, changed.copy(observedAt = 180_000L))
        val article = checkNotNull(store.article(articleId))
        assertEquals(changeTime, article.changeDetectedAt)
        assertEquals(1_000L, article.discoveredAt)
        assertEquals(articleTimestampLabel(changeTime, null, discoveredAt = null), article.timestampLabel(null))

        val sourceUpdateTime = 60_000L
        val dated = changed.copy(articles = changed.articles.map { it.copy(updatedAt = sourceUpdateTime) })
        store.recordRefresh(feedId, dated)
        val updated = checkNotNull(store.article(articleId))
        assertEquals(sourceUpdateTime, updated.updatedAt)
        assertEquals(articleTimestampLabel(sourceUpdateTime, null, discoveredAt = null), updated.timestampLabel(null))
    }

    @Test
    fun upgradingVersionSevenKeepsTheWebsiteScopeAndOriginalDetectionTime() {
        val watch = encodeWebsiteWatch(WebsiteWatch(null, null, false, setOf("publisher.example/"), setOf("sample")))
        val website = example.copy(
            htmlItemSelector = "main article", htmlWatch = watch, observedAt = 5_000L, etag = "\"old\"",
        )
        val id = store.subscribe(website, null)
        reopenFromVersion(7)

        val restored = store.subscriptions().single()
        assertEquals(watch, restored.htmlWatch)
        assertNull(restored.etag)
        assertEquals(5_000L, store.feedArticles(id, 10).articles.single().discoveredAt)
    }

    @Test
    fun upgradingVersionEightKeepsExistingRatingsVisitsAndCacheValidators() {
        val feed = store.subscribe(example.copy(etag = "\"current\""), null)
        val article = store.feedArticles(feed, 10).articles.single()
        store.recordView(article.id, 2500L)
        store.dismissArticle(article.id, ArticleRating.Bad)
        reopenFromVersion(8)
        assertEquals("\"current\"", store.subscriptions().single().etag)
        assertEquals(ArticleRating.Bad, store.ratingHistory(10).articles.single().rating)
        val history = store.history(10).articles.single()
        assertEquals(2500L, history.viewedAt)
        assertNull(history.originalOpenedAt)
        store.readableDatabase.rawQuery("SELECT 1 FROM article_action", null).use { assertFalse(it.moveToFirst()) }
    }

    @Test
    fun upgradingVersionTenKeepsArchivedArticlesAndUsesTheNotificationDefault() {
        val feed = store.subscribe(example, null)
        val article = store.feedArticles(feed, 10).articles.single()
        store.saveArchive(ArchiveCandidate(article.id, checkNotNull(article.url)), "保存済みの本文", Long.MAX_VALUE)
        reopenFromVersion(10)
        assertEquals(FeedNotificationMode.Default, store.subscriptions().single().notifications)
        assertEquals("保存済みの本文", store.article(article.id)?.archivedBody)
    }

    @Test
    fun upgradingVersionTwelveKeepsLegacyArchivedBodyReadable() {
        val feed = store.subscribe(example, null)
        val article = store.feedArticles(feed, 10).articles.single()
        val candidate = ArchiveCandidate(article.id, checkNotNull(article.url))
        assertTrue(store.saveArchive(candidate, "保存済みの本文", Long.MAX_VALUE))

        reopenFromVersion(12)

        val restored = checkNotNull(store.article(article.id))
        assertEquals(listOf(ArchivedParagraph("保存済みの本文")), archivedParagraphs(checkNotNull(restored.archivedBody), restored.archivedFormat))
        assertNull(restored.archivedFormat)
        assertTrue(store.saveArchive(candidate, "保存済みの本文", Long.MAX_VALUE, """{"0":[2,[]]}"""))
        assertFalse(store.saveArchive(candidate, null, Long.MAX_VALUE))
        assertEquals("""{"0":[2,[]]}""", store.article(article.id)?.archivedFormat)
    }

    @Test
    fun collectsOnlyNewVisibleArticlesAfterTheInitialRefresh() {
        val feedId = store.importSubscriptions(OpmlContents(listOf(
            OpmlFeed(example.url, example.title, notifications = FeedNotificationMode.On),
        ), 0, 0)).feedIds.single()
        assertNull(store.recordRefresh(feedId, example, notificationDefault = false).newArticles)
        store.setFeedKeywordFilter(feedId, KeywordFilter(KeywordFilterMode.Exclude, listOf("広告")))
        val updated = example.copy(articles = listOf(
            example.articles.single().copy(title = "修正した記事"),
            FeedArticle("newer", "新着記事", null, null, 800L),
            FeedArticle("older", "初めて取得した過去の記事", null, null, 700L),
            FeedArticle("ad", "広告", null, null, 3000L),
        ))
        assertEquals(
            NewFeedArticles(feedId, example.title, "新着記事", 2),
            store.recordRefresh(feedId, updated, notificationDefault = false).newArticles,
        )
        assertNull(store.recordRefresh(feedId, updated, notificationDefault = false).newArticles)
        assertNull(store.recordRefresh(feedId, null, notificationDefault = false).newArticles)
    }

    private fun reopenFromVersion(version: Int, removeNewerSchema: SQLiteDatabase.() -> Unit = {}) {
        store.writableDatabase.apply {
            if (version < 12) {
                execSQL("DROP TABLE quote_feedback")
                execSQL("DROP TABLE quote_model")
            }
            if (version < 11) execSQL("ALTER TABLE feed DROP COLUMN notifications")
            if (version < 10) {
                execSQL("DROP TRIGGER article_search_update")
                execSQL("DROP TRIGGER article_search_delete")
                execSQL("DROP TABLE article_vector")
                execSQL("DROP TABLE article_fts")
                execSQL("DROP TABLE article_archive")
                execSQL("DROP TABLE search_options")
            } else if (version < 13) {
                execSQL("ALTER TABLE article_archive DROP COLUMN formatting")
            }
            if (version < 9) {
                execSQL("DROP TABLE article_action")
                execSQL("ALTER TABLE article DROP COLUMN original_opened_at")
            }
            if (version < 8) {
                execSQL("ALTER TABLE article DROP COLUMN source_updated_at")
                execSQL("ALTER TABLE article DROP COLUMN source_published_at")
                execSQL("ALTER TABLE article DROP COLUMN change_detected_at")
            }
            if (version < 7) execSQL("ALTER TABLE feed DROP COLUMN html_watch")
            removeNewerSchema()
            this.version = version
        }
        store.close()
        store = FeedStore(RuntimeEnvironment.getApplication())
    }
}
