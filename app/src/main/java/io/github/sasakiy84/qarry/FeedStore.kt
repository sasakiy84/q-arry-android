package io.github.sasakiy84.qarry

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Subscription(
    val id: Long,
    val url: String,
    val title: String,
    val etag: String?,
    val modified: String?,
    val refreshedAt: Long?,
    val error: String?,
    val queueIds: Set<Long>,
    val htmlItemSelector: String? = null,
    val homeUrl: String? = null,
    val isFavorite: Boolean = false,
    val latestArticleAt: Long? = null,
)

enum class ArticleRating(val value: Int, val label: String) {
    Good(1, "Good"),
    Bad(-1, "Bad"),
}

data class Article(
    val id: Long,
    val feedTitle: String,
    val title: String?,
    val url: String?,
    val body: String?,
    val published: Long?,
    val read: Boolean,
    val thumbnailUrl: String? = null,
    val feedId: Long,
    val viewedAt: Long?,
    val dismissed: Boolean,
    val rating: ArticleRating?,
) {
    val displayTitle: String get() = title ?: url ?: "無題の記事"
}

data class FeedQueue(val id: Long, val name: String, val feedCount: Int)

data class ArticleListing(val articles: List<Article>, val hasMore: Boolean)

internal data class RatingKey(val feedUrl: String, val entryKey: String)

internal data class RatedArticle(
    val key: RatingKey,
    val articleId: Long?,
    val feedTitle: String,
    val title: String,
    val url: String?,
    val thumbnailUrl: String?,
    val rating: ArticleRating,
    val ratedAt: Long,
)

internal data class RatingListing(val articles: List<RatedArticle>, val hasMore: Boolean)

data class LibrarySnapshot(
    val subscriptions: List<Subscription> = emptyList(),
    val queues: List<FeedQueue> = emptyList(),
    val articles: List<Article> = emptyList(),
    val hasMore: Boolean = false,
)

class FeedStore(context: Context) : SQLiteOpenHelper(context, "q-arry.db", null, 5) {
    override fun onConfigure(db: SQLiteDatabase): Unit = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE feed (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                etag TEXT,
                modified TEXT,
                refreshed_at INTEGER,
                error TEXT,
                html_item_selector TEXT,
                home_url TEXT,
                is_favorite INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE article (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                feed_id INTEGER NOT NULL REFERENCES feed(id) ON DELETE CASCADE,
                entry_key TEXT NOT NULL,
                title TEXT NOT NULL,
                url TEXT,
                body TEXT NOT NULL,
                published INTEGER,
                discovered INTEGER NOT NULL,
                read INTEGER NOT NULL DEFAULT 0,
                thumbnail_url TEXT,
                viewed_at INTEGER,
                dismissed INTEGER NOT NULL DEFAULT 0,
                UNIQUE(feed_id, entry_key)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX article_newest ON article(COALESCE(published, discovered) DESC, id DESC)")
        db.execSQL(FEED_ARTICLE_INDEX)
        db.execSQL("CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE)")
        db.execSQL(
            """
            CREATE TABLE feed_queue (
                feed_id INTEGER NOT NULL REFERENCES feed(id) ON DELETE CASCADE,
                queue_id INTEGER NOT NULL REFERENCES queue(id) ON DELETE CASCADE,
                PRIMARY KEY(feed_id, queue_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX feed_queue_by_queue ON feed_queue(queue_id, feed_id)")
        createArticleFeedback(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..4 && newVersion == 5) { "No database migration from $oldVersion to $newVersion" }
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE feed ADD COLUMN html_item_selector TEXT")
            db.execSQL("ALTER TABLE article ADD COLUMN thumbnail_url TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE feed ADD COLUMN home_url TEXT")
            db.execSQL("ALTER TABLE article ADD COLUMN viewed_at INTEGER")
            db.execSQL("ALTER TABLE article ADD COLUMN dismissed INTEGER NOT NULL DEFAULT 0")
            createArticleFeedback(db)
            // Fetch the feed metadata once so existing subscriptions can acquire their site links.
            db.execSQL("UPDATE feed SET etag = NULL, modified = NULL")
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE feed ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL(FEED_ARTICLE_INDEX)
    }

    private fun createArticleFeedback(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX article_viewed ON article(viewed_at DESC, id DESC) WHERE viewed_at IS NOT NULL")
        // Keep feedback when a feed and its articles are removed from the library.
        db.execSQL(
            """
            CREATE TABLE article_feedback (
                feed_url TEXT NOT NULL,
                entry_key TEXT NOT NULL,
                article_url TEXT,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                published_at INTEGER,
                rating INTEGER NOT NULL CHECK(rating IN (-1, 1)),
                rated_at INTEGER NOT NULL,
                PRIMARY KEY(feed_url, entry_key)
            )
            """.trimIndent(),
        )
    }

    fun load(queueId: Long?, unreadOnly: Boolean, limit: Int): LibrarySnapshot =
        readableDatabase.transaction {
            val queues = rawQuery(
                """
                SELECT q.id, q.name, COUNT(fq.feed_id)
                FROM queue q LEFT JOIN feed_queue fq ON fq.queue_id = q.id
                GROUP BY q.id ORDER BY q.id
                """.trimIndent(),
                null,
            ).use { cursor -> cursor.rows { FeedQueue(getLong(0), getString(1), getInt(2)) } }
            val filters = buildList {
                add(ACTIVE_ARTICLE)
                if (queueId != null) add("EXISTS (SELECT 1 FROM feed_queue fq WHERE fq.feed_id = a.feed_id AND fq.queue_id = ?)")
                if (unreadOnly) add("a.read = 0")
            }
            val listing = articleListing(filters.joinToString(" AND "), listOfNotNull(queueId?.toString()), limit)
            LibrarySnapshot(subscriptions(this), queues, listing.articles, listing.hasMore)
        }

    fun subscriptions(): List<Subscription> = subscriptions(readableDatabase)

    fun feedArticles(feedId: Long, limit: Int): ArticleListing =
        readableDatabase.articleListing("$ACTIVE_ARTICLE AND a.feed_id = ?", listOf(feedId.toString()), limit)

    fun history(limit: Int): ArticleListing =
        readableDatabase.articleListing("a.viewed_at IS NOT NULL", emptyList(), limit, "a.viewed_at DESC, a.id DESC")

    internal fun ratingHistory(limit: Int): RatingListing {
        val articles = readableDatabase.rawQuery(
            """
            SELECT r.feed_url, r.entry_key, a.id, COALESCE(f.title, r.feed_url),
                   COALESCE(NULLIF(a.title, ''), NULLIF(r.title, ''), a.url, r.article_url, '無題の記事'),
                   COALESCE(a.url, r.article_url), a.thumbnail_url, r.rating, r.rated_at
            FROM article_feedback r
            LEFT JOIN feed f ON f.url = r.feed_url
            LEFT JOIN article a ON a.feed_id = f.id AND a.entry_key = r.entry_key
            ORDER BY r.rated_at DESC, r.rowid DESC LIMIT ?
            """.trimIndent(),
            arrayOf((limit + 1).toString()),
        ).use { cursor ->
            cursor.rows {
                RatedArticle(
                    RatingKey(getString(0), getString(1)), nullableLong(2), getString(3), getString(4),
                    getString(5), getString(6), ArticleRating.entries.first { it.value == getInt(7) }, getLong(8),
                )
            }
        }
        return RatingListing(articles.take(limit), articles.size > limit)
    }

    fun article(id: Long): Article? = readableDatabase.rawQuery(
        "${articleQuery("a.body")} WHERE a.id = ?",
        arrayOf(id.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.readArticle() else null }

    private fun SQLiteDatabase.articleListing(
        where: String,
        args: List<String>,
        limit: Int,
        order: String = "COALESCE(a.published, a.discovered) DESC, a.id DESC",
    ): ArticleListing {
        val articles = rawQuery(
            "${articleQuery("SUBSTR(a.body, 1, 240)")} WHERE $where ORDER BY $order LIMIT ?",
            (args + (limit + 1).toString()).toTypedArray(),
        ).use { cursor -> cursor.rows { readArticle() } }
        return ArticleListing(articles.take(limit), articles.size > limit)
    }

    private fun subscriptions(db: SQLiteDatabase): List<Subscription> {
        val membership = db.rawQuery("SELECT feed_id, queue_id FROM feed_queue", null).use { cursor ->
            cursor.rows { getLong(0) to getLong(1) }.groupBy({ it.first }, { it.second })
        }
        return db.rawQuery(
            """
            SELECT f.id, f.url, f.title, f.etag, f.modified, f.refreshed_at, f.error, f.html_item_selector,
                   f.home_url, f.is_favorite,
                   (SELECT COALESCE(a.published, a.discovered) FROM article a WHERE a.feed_id = f.id
                    ORDER BY COALESCE(a.published, a.discovered) DESC LIMIT 1)
            FROM feed f ORDER BY f.title COLLATE NOCASE
            """.trimIndent(),
            null,
        ).use { cursor ->
            cursor.rows {
                val id = getLong(0)
                Subscription(
                    id = id,
                    url = getString(1),
                    title = getString(2),
                    etag = getString(3),
                    modified = getString(4),
                    refreshedAt = nullableLong(5),
                    error = getString(6),
                    queueIds = membership[id].orEmpty().toSet(),
                    htmlItemSelector = getString(7),
                    homeUrl = getString(8),
                    isFavorite = getInt(9) != 0,
                    latestArticleAt = nullableLong(10),
                )
            }
        }
    }

    fun subscribe(document: FeedDocument, queueId: Long?): Long = writableDatabase.transaction {
        val inserted = insertFeed(document.url, document.title)
        val id = if (inserted != -1L) {
            inserted
        } else {
            rawQuery("SELECT id FROM feed WHERE url = ?", arrayOf(document.url)).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }
        if (queueId != null) addMembership(id, queueId)
        saveDocument(id, document, System.currentTimeMillis())
        id
    }

    internal fun importSubscriptions(feeds: List<OpmlFeed>): Set<Long> = writableDatabase.transaction {
        feeds.mapNotNull { feed -> insertFeed(feed.url, feed.title).takeIf { it != -1L } }.toSet()
    }

    private fun SQLiteDatabase.insertFeed(url: String, title: String): Long {
        val values = ContentValues().apply {
            put("url", url)
            put("title", title)
        }
        return insertWithOnConflict("feed", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun recordRefresh(feedId: Long, document: FeedDocument?): Unit = writableDatabase.transaction {
        val now = System.currentTimeMillis()
        if (document != null) {
            saveDocument(feedId, document, now)
        } else {
            execSQL("UPDATE feed SET refreshed_at = ?, error = NULL WHERE id = ?", arrayOf(now, feedId))
        }
    }

    fun recordFailure(feedId: Long, message: String): Unit =
        writableDatabase.execSQL("UPDATE feed SET error = ? WHERE id = ?", arrayOf<Any>(message, feedId))

    fun setMembership(feedId: Long, queueIds: Set<Long>): Unit = writableDatabase.transaction {
        delete("feed_queue", "feed_id = ?", arrayOf(feedId.toString()))
        queueIds.forEach { addMembership(feedId, it) }
    }

    private fun SQLiteDatabase.addMembership(feedId: Long, queueId: Long): Unit =
        execSQL(
            """
            INSERT OR IGNORE INTO feed_queue(feed_id, queue_id)
            SELECT f.id, q.id FROM feed f, queue q WHERE f.id = ? AND q.id = ?
            """.trimIndent(),
            arrayOf(feedId, queueId),
        )

    fun queueName(id: Long?, name: String): Boolean {
        val values = ContentValues().apply { put("name", name) }
        val db = writableDatabase
        return if (id == null) {
            db.insertWithOnConflict("queue", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        } else {
            db.updateWithOnConflict("queue", values, "id = ?", arrayOf(id.toString()), SQLiteDatabase.CONFLICT_IGNORE) > 0
        }
    }

    fun removeQueue(id: Long): Unit =
        writableDatabase.execSQL("DELETE FROM queue WHERE id = ?", arrayOf(id))

    fun removeFeed(id: Long): Unit =
        writableDatabase.execSQL("DELETE FROM feed WHERE id = ?", arrayOf(id))

    fun setFeedFavorite(id: Long, favorite: Boolean): Unit =
        writableDatabase.execSQL("UPDATE feed SET is_favorite = ? WHERE id = ?", arrayOf(if (favorite) 1 else 0, id))

    fun setRead(id: Long, read: Boolean): Unit =
        writableDatabase.execSQL("UPDATE article SET read = ? WHERE id = ?", arrayOf(if (read) 1 else 0, id))

    fun recordView(id: Long, viewedAt: Long = System.currentTimeMillis()): Unit =
        writableDatabase.execSQL("UPDATE article SET read = 1, viewed_at = ? WHERE id = ?", arrayOf(viewedAt, id))

    fun dismissArticle(id: Long, rating: ArticleRating?): Unit = writableDatabase.transaction {
        val dismissed = ContentValues().apply { put("dismissed", 1) }
        if (update("article", dismissed, "id = ? AND dismissed = 0", arrayOf(id.toString())) == 0) return@transaction
        if (rating != null) {
            execSQL(
                """
                INSERT OR IGNORE INTO article_feedback(feed_url, entry_key, article_url, title, body, published_at, rating, rated_at)
                SELECT f.url, a.entry_key, a.url, a.title, a.body, a.published, ?, ?
                FROM article a JOIN feed f ON f.id = a.feed_id WHERE a.id = ?
                """.trimIndent(),
                arrayOf<Any>(rating.value, System.currentTimeMillis(), id),
            )
        }
    }

    internal fun changeRating(key: RatingKey, rating: ArticleRating?): Unit = writableDatabase.transaction {
        val args = arrayOf(key.feedUrl, key.entryKey)
        if (rating != null) {
            val values = ContentValues().apply { put("rating", rating.value) }
            update("article_feedback", values, "feed_url = ? AND entry_key = ?", args)
        } else if (delete("article_feedback", "feed_url = ? AND entry_key = ?", args) > 0) {
            execSQL(
                "UPDATE article SET dismissed = 0 WHERE feed_id = (SELECT id FROM feed WHERE url = ?) AND entry_key = ?",
                args,
            )
        }
    }

    private fun SQLiteDatabase.saveDocument(feedId: Long, document: FeedDocument, now: Long) {
        val metadata = ContentValues().apply {
            put("title", document.title)
            put("etag", document.etag)
            put("modified", document.modified)
            put("html_item_selector", document.htmlItemSelector)
            document.home?.let { put("home_url", it) }
            put("refreshed_at", now)
            putNull("error")
        }
        if (update("feed", metadata, "id = ?", arrayOf(feedId.toString())) == 0) return
        document.articles.asReversed().forEach { article ->
            execSQL(
                """
                INSERT INTO article(feed_id, entry_key, title, url, body, published, discovered, thumbnail_url)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(feed_id, entry_key) DO UPDATE SET
                  title = excluded.title, url = excluded.url, body = excluded.body,
                  thumbnail_url = excluded.thumbnail_url,
                  published = COALESCE(excluded.published, article.published)
                """.trimIndent(),
                arrayOf<Any?>(
                    feedId, article.key, article.title.orEmpty(), article.url, article.body.orEmpty(),
                    article.published, now, article.thumbnailUrl,
                ),
            )
        }
    }
}

private inline fun <T> Cursor.rows(readRow: Cursor.() -> T): List<T> =
    buildList { while (moveToNext()) add(readRow()) }

private fun Cursor.nullableLong(column: Int): Long? = if (isNull(column)) null else getLong(column)

private fun Cursor.readArticle(): Article =
    Article(
        id = getLong(0), feedTitle = getString(1), title = getString(2), url = getString(3),
        body = getString(4), published = nullableLong(5), read = getInt(6) != 0, thumbnailUrl = getString(7),
        feedId = getLong(8), viewedAt = nullableLong(9), dismissed = getInt(10) != 0,
        rating = ArticleRating.entries.firstOrNull { it.value == getInt(11) },
    )

private const val ACTIVE_ARTICLE = "a.dismissed = 0 AND r.rating IS NULL"
private const val FEED_ARTICLE_INDEX =
    "CREATE INDEX article_feed_newest ON article(feed_id, COALESCE(published, discovered) DESC)"

private fun articleQuery(body: String): String = """
    SELECT a.id, f.title, NULLIF(a.title, ''), a.url, NULLIF($body, ''), a.published, a.read,
           a.thumbnail_url, a.feed_id, a.viewed_at, a.dismissed OR r.rating IS NOT NULL, r.rating
    FROM article a
    JOIN feed f ON f.id = a.feed_id
    LEFT JOIN article_feedback r ON r.feed_url = f.url AND r.entry_key = a.entry_key
""".trimIndent()

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransactionNonExclusive()
    return try {
        block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
