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
)

data class Article(
    val id: Long,
    val feedTitle: String,
    val title: String?,
    val url: String?,
    val body: String?,
    val published: Long?,
    val read: Boolean,
    val thumbnailUrl: String? = null,
) {
    val displayTitle: String get() = title ?: url ?: "無題の記事"
}

data class FeedQueue(val id: Long, val name: String, val feedCount: Int)

data class LibrarySnapshot(
    val subscriptions: List<Subscription> = emptyList(),
    val queues: List<FeedQueue> = emptyList(),
    val articles: List<Article> = emptyList(),
    val hasMore: Boolean = false,
)

class FeedStore(context: Context) : SQLiteOpenHelper(context, "q-arry.db", null, 2) {
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
                html_item_selector TEXT
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
                UNIQUE(feed_id, entry_key)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX article_newest ON article(COALESCE(published, discovered) DESC, id DESC)")
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "No database migration from $oldVersion to $newVersion" }
        db.execSQL("ALTER TABLE feed ADD COLUMN html_item_selector TEXT")
        db.execSQL("ALTER TABLE article ADD COLUMN thumbnail_url TEXT")
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
            val filter = buildList {
                if (queueId != null) add("EXISTS (SELECT 1 FROM feed_queue fq WHERE fq.feed_id = a.feed_id AND fq.queue_id = ?)")
                if (unreadOnly) add("a.read = 0")
            }.joinToString(" AND ").takeIf(String::isNotEmpty)?.let { "WHERE $it" }.orEmpty()
            val args = listOfNotNull(queueId?.toString(), (limit + 1).toString()).toTypedArray()
            val articles = rawQuery(
                """
                SELECT a.id, f.title, NULLIF(a.title, ''), a.url, NULLIF(SUBSTR(a.body, 1, 240), ''), a.published, a.read, a.thumbnail_url
                FROM article a JOIN feed f ON f.id = a.feed_id
                $filter
                ORDER BY COALESCE(a.published, a.discovered) DESC, a.id DESC LIMIT ?
                """.trimIndent(),
                args,
            ).use { cursor ->
                cursor.rows {
                    readArticle()
                }
            }
            LibrarySnapshot(subscriptions(this), queues, articles.take(limit), articles.size > limit)
        }

    fun subscriptions(): List<Subscription> = subscriptions(readableDatabase)

    fun article(id: Long): Article? = readableDatabase.rawQuery(
        """
        SELECT a.id, f.title, NULLIF(a.title, ''), a.url, NULLIF(a.body, ''), a.published, a.read, a.thumbnail_url
        FROM article a JOIN feed f ON f.id = a.feed_id WHERE a.id = ?
        """.trimIndent(),
        arrayOf(id.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.readArticle() else null }

    private fun subscriptions(db: SQLiteDatabase): List<Subscription> {
        val membership = db.rawQuery("SELECT feed_id, queue_id FROM feed_queue", null).use { cursor ->
            cursor.rows { getLong(0) to getLong(1) }.groupBy({ it.first }, { it.second })
        }
        return db.rawQuery(
            "SELECT id, url, title, etag, modified, refreshed_at, error, html_item_selector FROM feed ORDER BY title COLLATE NOCASE",
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
                )
            }
        }
    }

    fun subscribe(document: FeedDocument, queueId: Long?): Long = writableDatabase.transaction {
        val values = ContentValues().apply {
            put("url", document.url)
            put("title", document.title)
        }
        insertWithOnConflict("feed", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        val id = rawQuery("SELECT id FROM feed WHERE url = ?", arrayOf(document.url)).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        if (queueId != null) addMembership(id, queueId)
        saveDocument(id, document, System.currentTimeMillis())
        id
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

    fun setRead(id: Long, read: Boolean): Unit =
        writableDatabase.execSQL("UPDATE article SET read = ? WHERE id = ?", arrayOf(if (read) 1 else 0, id))

    private fun SQLiteDatabase.saveDocument(feedId: Long, document: FeedDocument, now: Long) {
        val metadata = ContentValues().apply {
            put("title", document.title)
            put("etag", document.etag)
            put("modified", document.modified)
            put("html_item_selector", document.htmlItemSelector)
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
    Article(getLong(0), getString(1), getString(2), getString(3), getString(4), nullableLong(5), getInt(6) != 0, getString(7))

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransactionNonExclusive()
    return try {
        block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
