package io.github.sasakiy84.qarry

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

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
    fun upgradingTheOriginalDatabaseRetainsExistingRssEntriesAndQueueMemberships() {
        assertTrue(store.queueName(null, "Research"))
        val queueId = store.load(null, false, 10).queues.single().id
        store.subscribe(example, queueId)
        val articleId = store.load(queueId, false, 10).articles.single().id
        store.setRead(articleId, true)
        // The two added columns are the only difference from the installed version 1 schema.
        store.writableDatabase.apply {
            execSQL("ALTER TABLE feed DROP COLUMN html_item_selector")
            execSQL("ALTER TABLE article DROP COLUMN thumbnail_url")
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
    }
}
