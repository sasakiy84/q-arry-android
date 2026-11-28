package io.github.sasakiy84.qarry

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class QarryApplication : Application() {
    internal val backgroundUpdates by lazy { BackgroundUpdates(this) }

    val library: FeedLibrary by lazy {
        FeedLibrary(
            FeedStore(this).apply { setWriteAheadLoggingEnabled(true) }, FeedClient(),
            File(noBackupFilesDir, "bekko-a8m-c721113"), this,
        )
    }

    override fun onCreate() {
        super.onCreate()
        backgroundUpdates.schedule()
    }
}

class FeedLibrary(private val store: FeedStore, private val client: FeedClient, modelDir: File? = null, context: Context? = null) {
    internal val archive = ArticleArchive(store, client, modelDir)
    internal val quotes = QuoteLearning(QuoteStore(store), context?.assets?.let(::BekkoQuoteEncoder))
    private val notifications = context?.let(::FeedNotifications)
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()
    private val activeRefresh = MutableStateFlow(false)
    val refreshing = activeRefresh.asStateFlow()
    private val refreshLock = Mutex()
    private val metadataLookups = LruCache<String, MetadataLookup>(256)
    private val metadataConnections = Semaphore(3)

    suspend fun load(queueId: Long?, unreadOnly: Boolean, limit: Int, unassignedOnly: Boolean = false): LibrarySnapshot =
        runInterruptible(Dispatchers.IO) { store.load(queueId, unreadOnly, limit, unassignedOnly) }

    suspend fun article(id: Long): Article? = runInterruptible(Dispatchers.IO) { store.article(id) }

    suspend fun feedArticles(feedId: Long, limit: Int, applyKeywordFilter: Boolean = true): ArticleListing =
        runInterruptible(Dispatchers.IO) { store.feedArticles(feedId, limit, applyKeywordFilter) }

    suspend fun history(limit: Int): ArticleListing =
        runInterruptible(Dispatchers.IO) { store.history(limit) }

    internal suspend fun ratingHistory(limit: Int): RatingListing =
        runInterruptible(Dispatchers.IO) { store.ratingHistory(limit) }

    private fun cachedMetadataLookup(url: String): MetadataLookup? =
        metadataLookups.get(url)?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }

    internal fun cachedArticleMetadata(url: String): ArticleMetadata? = cachedMetadataLookup(url)?.metadata

    internal suspend fun articleMetadata(url: String): ArticleMetadata? {
        cachedMetadataLookup(url)?.let { return it.metadata }
        return metadataConnections.withPermit {
            cachedMetadataLookup(url)?.let { return@withPermit it.metadata }
            val metadata = runInterruptible(Dispatchers.IO) {
                try {
                    client.articleMetadata(url)
                } catch (_: IOException) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    null
                }
            }
            metadataLookups.put(url, MetadataLookup(metadata, SystemClock.elapsedRealtime() + METADATA_LOOKUP_TTL))
            metadata
        }
    }

    internal suspend fun search(url: String): FeedDiscovery =
        runInterruptible(Dispatchers.IO) { client.discoverForRegistration(url) }

    internal suspend fun inspectWebsite(url: String): WebsiteScopes =
        runInterruptible(Dispatchers.IO) { client.inspectWebsite(url) }

    internal suspend fun updateWebsiteScope(feedId: Long, document: FeedDocument): Boolean =
        change { store.updateWebsiteScope(feedId, document) }

    internal suspend fun importOpml(openInput: () -> InputStream?): FeedImportResult = change {
        val input = openInput() ?: throw IOException("OPML stream unavailable")
        val contents = input.use(::readOpml)
        val imported = store.importSubscriptions(contents)
        Log.i(FEED_LOG, "Imported feeds=${imported.feedIds.size}")
        imported.copy(duplicateCount = imported.duplicateCount + contents.feeds.size - imported.feedIds.size)
    }

    internal suspend fun exportOpml(openOutput: () -> OutputStream?): Int = runInterruptible(Dispatchers.IO) {
        val contents = store.opmlSubscriptions()
        val document = encodeOpml(contents.feeds, contents.queues)
        val output = openOutput() ?: throw IOException("OPML output stream unavailable")
        output.use { it.write(document) }
        contents.feeds.size
    }

    internal suspend fun exportRatingHistory(openOutput: () -> OutputStream?): Int = runInterruptible(Dispatchers.IO) {
        val output = openOutput() ?: throw IOException("Rating history output stream unavailable")
        output.writer(Charsets.UTF_8).buffered().use(store::writeRatingHistory)
    }

    suspend fun subscribe(document: FeedDocument, queueId: Long?): Long = change {
        store.subscribe(document, queueId).also {
            Log.i(FEED_LOG, "Subscribed feed=$it entries=${document.articles.size}")
        }
    }

    suspend fun setMembership(feedId: Long, queueIds: Set<Long>, keywordFilters: Map<Long, KeywordFilter>): Unit = change {
        store.setMembership(feedId, queueIds, keywordFilters).also {
            Log.i(FEED_LOG, "Assigned feed=$feedId queues=${queueIds.size}")
        }
    }

    suspend fun setFeedKeywordFilter(feedId: Long, rule: KeywordFilter?): Unit = change { store.setFeedKeywordFilter(feedId, rule) }

    suspend fun nameQueue(id: Long?, name: String): Boolean = change { store.queueName(id, name) }
    suspend fun removeQueue(id: Long): Unit = change { store.removeQueue(id) }
    suspend fun removeFeed(id: Long): Unit = change { store.removeFeed(id) }
    suspend fun setFeedFavorite(id: Long, favorite: Boolean): Unit = change { store.setFeedFavorite(id, favorite) }
    internal suspend fun setFeedNotifications(id: Long, mode: FeedNotificationMode): Unit =
        change { store.setFeedNotifications(id, mode) }
    suspend fun markRead(id: Long, read: Boolean): Unit = change { store.setRead(id, read) }
    suspend fun markAllRead(queueId: Long?, unassignedOnly: Boolean = false): Int =
        change { store.markAllRead(queueId, unassignedOnly) }
    suspend fun recordView(id: Long): Unit = change { store.recordView(id) }
    suspend fun recordOriginalOpen(id: Long, queueId: Long?): Unit = change { store.recordOriginalOpen(id, queueId) }
    internal suspend fun recordArchivedOriginalOpen(key: RatingKey, url: String): Unit =
        change { store.recordArchivedOriginalOpen(key, url) }
    suspend fun dismissArticle(id: Long, rating: ArticleRating?, queueId: Long?): Unit =
        change { store.dismissArticle(id, rating, queueId) }
    suspend fun dismissQueueArticles(queueId: Long, unreadOnly: Boolean): Int =
        change { store.dismissQueueArticles(queueId, unreadOnly) }
    suspend fun dismissFeedArticles(feedId: Long): Int = change { store.dismissFeedArticles(feedId) }
    internal suspend fun changeRating(key: RatingKey, rating: ArticleRating?): Unit = change { store.changeRating(key, rating) }

    suspend fun refresh(onlyFeed: Long? = null, requestClient: FeedClient = client) {
        if (!refreshLock.tryLock()) return
        try {
            refreshFeeds(onlyFeed?.let(::setOf), requestClient)
        } finally {
            refreshLock.unlock()
        }
    }

    internal suspend fun refreshImported(feedIds: Set<Long>) {
        if (feedIds.isEmpty()) return
        refreshLock.withLock { refreshFeeds(feedIds, client) }
    }

    private suspend fun refreshFeeds(feedIds: Set<Long>?, requestClient: FeedClient): Unit = coroutineScope {
        activeRefresh.value = true
        try {
            val feeds = runInterruptible(Dispatchers.IO) { store.subscriptions() }
                .filter { feedIds == null || it.id in feedIds }
            val connections = Semaphore(4)
            val refreshed = feeds.map { feed ->
                async {
                    connections.withPermit {
                        runInterruptible(Dispatchers.IO) {
                            try {
                                val oldKeys = if (feed.htmlWatch == null && feed.htmlItemSelector?.contains(":nth-child(") == true) {
                                    store.recentWebsiteKeys(feed.id)
                                } else emptySet()
                                val document = requestClient.refresh(
                                    feed.url, feed.etag, feed.modified, feed.htmlItemSelector, feed.htmlWatch, oldKeys,
                                )
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                val notificationDefault = notifications?.takeIf { it.canPost() }?.defaultEnabled
                                val result = store.recordRefresh(
                                    feed.id, document, expected = feed, notificationDefault = notificationDefault,
                                )
                                if (result.newArticles != null) notifications?.post(result.newArticles)
                                result.applied
                            } catch (error: FeedRequestException) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                store.recordRefresh(feed.id, null, expected = feed, error = error.message.orEmpty())
                                Log.w(FEED_LOG, "Refresh failed feed=${feed.id} ${error.message}")
                                false
                            }
                        }.also { changes.update { count -> count + 1 } }
                    }
                }
            }.awaitAll()
            Log.i(FEED_LOG, "Refresh checked=${feeds.size} succeeded=${refreshed.count { it }}")
        } finally {
            activeRefresh.value = false
        }
    }

    private suspend fun <T> change(block: () -> T): T =
        runInterruptible(Dispatchers.IO) { block().also { changes.update { count -> count + 1 } } }
}

internal data class FeedImportResult(
    val feedIds: Set<Long>, val duplicateCount: Int, val unsupportedCount: Int, val createdQueues: Int = 0,
)

fun Context.feedLibrary(): FeedLibrary = (applicationContext as QarryApplication).library

private const val FEED_LOG = "QarryFeeds"
private const val METADATA_LOOKUP_TTL = 30 * 60 * 1_000L
private data class MetadataLookup(val metadata: ArticleMetadata?, val expiresAt: Long)
