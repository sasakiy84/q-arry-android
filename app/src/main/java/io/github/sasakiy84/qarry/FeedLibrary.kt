package io.github.sasakiy84.qarry

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.io.InputStream

class QarryApplication : Application() {
    val library: FeedLibrary by lazy {
        FeedLibrary(FeedStore(this).apply { setWriteAheadLoggingEnabled(true) }, FeedClient())
    }

    override fun onCreate() {
        super.onCreate()
        val scheduler = getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(REFRESH_JOB) == null) {
            val job = JobInfo.Builder(REFRESH_JOB, ComponentName(this, FeedRefreshService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(60 * 60 * 1_000L)
                .setPersisted(true)
                .build()
            val scheduled = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
            Log.i(FEED_LOG, "Periodic refresh scheduled=$scheduled")
        }
    }
}

class FeedRefreshService : JobService() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var refreshJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        refreshJob = scope.launch {
            var retry = false
            try {
                (application as QarryApplication).library.refresh()
            } catch (_: SQLiteException) {
                Log.w(FEED_LOG, "Background refresh could not access the database")
                retry = true
            } finally {
                if (refreshJob?.isCancelled == false) jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        refreshJob?.cancel()
        refreshJob = null
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class FeedLibrary(private val store: FeedStore, private val client: FeedClient) {
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()
    private val activeRefresh = MutableStateFlow(false)
    val refreshing = activeRefresh.asStateFlow()
    private val refreshLock = Mutex()
    private val thumbnailLookups = LruCache<String, ThumbnailLookup>(256)
    private val thumbnailConnections = Semaphore(3)

    suspend fun load(queueId: Long?, unreadOnly: Boolean, limit: Int): LibrarySnapshot =
        runInterruptible(Dispatchers.IO) { store.load(queueId, unreadOnly, limit) }

    suspend fun article(id: Long): Article? = runInterruptible(Dispatchers.IO) { store.article(id) }

    suspend fun feedArticles(feedId: Long, limit: Int): ArticleListing =
        runInterruptible(Dispatchers.IO) { store.feedArticles(feedId, limit) }

    suspend fun history(limit: Int): ArticleListing =
        runInterruptible(Dispatchers.IO) { store.history(limit) }

    internal suspend fun ratingHistory(limit: Int): RatingListing =
        runInterruptible(Dispatchers.IO) { store.ratingHistory(limit) }

    suspend fun articleThumbnail(url: String): String? = thumbnailConnections.withPermit {
        thumbnailLookups.get(url)?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }
            ?.let { return@withPermit it.imageUrl }
        val imageUrl = runInterruptible(Dispatchers.IO) {
            try {
                client.articleThumbnail(url)
            } catch (_: FeedRequestException) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                null
            }
        }
        thumbnailLookups.put(url, ThumbnailLookup(imageUrl, SystemClock.elapsedRealtime() + THUMBNAIL_LOOKUP_TTL))
        imageUrl
    }

    internal suspend fun search(url: String): FeedDiscovery =
        runInterruptible(Dispatchers.IO) { client.discoverForRegistration(url) }

    internal suspend fun importOpml(openInput: () -> InputStream?): FeedImportResult = change {
        val input = openInput() ?: throw IOException("OPML stream unavailable")
        val contents = input.use(::readOpml)
        val added = store.importSubscriptions(contents.feeds)
        Log.i(FEED_LOG, "Imported feeds=${added.size}")
        FeedImportResult(
            added,
            contents.duplicateCount + contents.feeds.size - added.size,
            contents.unsupportedCount,
        )
    }

    suspend fun subscribe(document: FeedDocument, queueId: Long?): Long = change {
        store.subscribe(document, queueId).also {
            Log.i(FEED_LOG, "Subscribed feed=$it entries=${document.articles.size}")
        }
    }

    suspend fun setMembership(feedId: Long, queueIds: Set<Long>): Unit = change {
        store.setMembership(feedId, queueIds).also {
            Log.i(FEED_LOG, "Assigned feed=$feedId queues=${queueIds.size}")
        }
    }

    suspend fun nameQueue(id: Long?, name: String): Boolean = change { store.queueName(id, name) }
    suspend fun removeQueue(id: Long): Unit = change { store.removeQueue(id) }
    suspend fun removeFeed(id: Long): Unit = change { store.removeFeed(id) }
    suspend fun setFeedFavorite(id: Long, favorite: Boolean): Unit = change { store.setFeedFavorite(id, favorite) }
    suspend fun markRead(id: Long, read: Boolean): Unit = change { store.setRead(id, read) }
    suspend fun recordView(id: Long): Unit = change { store.recordView(id) }
    suspend fun dismissArticle(id: Long, rating: ArticleRating?): Unit = change { store.dismissArticle(id, rating) }
    internal suspend fun changeRating(key: RatingKey, rating: ArticleRating?): Unit = change { store.changeRating(key, rating) }

    suspend fun refresh(onlyFeed: Long? = null) {
        if (!refreshLock.tryLock()) return
        try {
            refreshFeeds(onlyFeed?.let(::setOf))
        } finally {
            refreshLock.unlock()
        }
    }

    internal suspend fun refreshImported(feedIds: Set<Long>) {
        if (feedIds.isEmpty()) return
        refreshLock.withLock { refreshFeeds(feedIds) }
    }

    private suspend fun refreshFeeds(feedIds: Set<Long>?): Unit = coroutineScope {
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
                                val document = client.refresh(feed.url, feed.etag, feed.modified, feed.htmlItemSelector)
                                store.recordRefresh(feed.id, document)
                                true
                            } catch (error: FeedRequestException) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                store.recordFailure(feed.id, error.message.orEmpty())
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

internal data class FeedImportResult(val feedIds: Set<Long>, val duplicateCount: Int, val unsupportedCount: Int)

fun Context.feedLibrary(): FeedLibrary = (applicationContext as QarryApplication).library

private const val FEED_LOG = "QarryFeeds"
private const val REFRESH_JOB = 241
private const val THUMBNAIL_LOOKUP_TTL = 30 * 60 * 1_000L
private data class ThumbnailLookup(val imageUrl: String?, val expiresAt: Long)
