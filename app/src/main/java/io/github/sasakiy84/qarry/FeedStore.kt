package io.github.sasakiy84.qarry

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import android.util.JsonWriter
import java.io.Writer
import java.time.Instant
import java.util.PriorityQueue

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
    val keywordFilter: KeywordFilter? = null,
    val queueKeywordFilters: Map<Long, KeywordFilter> = emptyMap(),
    val htmlWatch: String? = null,
    val notifications: FeedNotificationMode = FeedNotificationMode.Default,
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
    val updatedAt: Long? = null,
    val publishedTime: Long? = published,
    val changeDetectedAt: Long? = null,
    val discoveredAt: Long? = null,
    val originalOpenedAt: Long? = null,
    val archivedBody: String? = null,
) {
    val displayTitle: String get() = title ?: url ?: "無題の記事"
}

data class FeedQueue(val id: Long, val name: String, val feedCount: Int)

data class ArticleListing(val articles: List<Article>, val hasMore: Boolean)

internal data class RatingKey(val feedUrl: String, val entryKey: String)

internal enum class ArticleAction {
    OriginalOpened, RemovedIndividually, RemovedFromQueueInBulk, RemovedFromFeedInBulk,
}

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
    val hasUnread: Boolean = articles.any { !it.read },
)

class FeedStore(context: Context) : SQLiteOpenHelper(context, "q-arry.db", null, 12) {
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
                is_favorite INTEGER NOT NULL DEFAULT 0,
                html_watch TEXT,
                notifications INTEGER NOT NULL DEFAULT 0
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
                source_updated_at INTEGER,
                source_published_at INTEGER,
                change_detected_at INTEGER,
                original_opened_at INTEGER,
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
        createArticleActions(db)
        createKeywordFilters(db)
        createArticleSearch(db)
        createQuoteTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..11 && newVersion == 12) { "No database migration from $oldVersion to $newVersion" }
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE feed ADD COLUMN html_item_selector TEXT")
            db.execSQL("ALTER TABLE article ADD COLUMN thumbnail_url TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE feed ADD COLUMN home_url TEXT")
            db.execSQL("ALTER TABLE article ADD COLUMN viewed_at INTEGER")
            db.execSQL("ALTER TABLE article ADD COLUMN dismissed INTEGER NOT NULL DEFAULT 0")
            createArticleFeedback(db)
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE feed ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 5) db.execSQL(FEED_ARTICLE_INDEX)
        if (oldVersion < 6) createKeywordFilters(db)
        if (oldVersion < 7) db.execSQL("ALTER TABLE feed ADD COLUMN html_watch TEXT")
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE article ADD COLUMN source_updated_at INTEGER")
            db.execSQL("ALTER TABLE article ADD COLUMN source_published_at INTEGER")
            db.execSQL("ALTER TABLE article ADD COLUMN change_detected_at INTEGER")
            // Re-read feed entries once to recover source times that older versions did not store.
            db.execSQL("UPDATE feed SET etag = NULL, modified = NULL")
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE article ADD COLUMN original_opened_at INTEGER")
            createArticleActions(db)
        }
        if (oldVersion < 10) createArticleSearch(db)
        if (oldVersion < 11) db.execSQL("ALTER TABLE feed ADD COLUMN notifications INTEGER NOT NULL DEFAULT 0")
        createQuoteTables(db)
    }

    private fun createArticleSearch(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE article_archive (
                article_id INTEGER PRIMARY KEY REFERENCES article(id) ON DELETE CASCADE,
                body TEXT,
                retry_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE VIRTUAL TABLE article_fts USING fts4(title, body, tokenize=simple)")
        db.execSQL("CREATE TABLE article_vector (article_id INTEGER PRIMARY KEY REFERENCES article(id) ON DELETE CASCADE, embedding BLOB NOT NULL)")
        db.execSQL("CREATE TABLE search_options (id INTEGER PRIMARY KEY CHECK(id = 1), semantic_enabled INTEGER NOT NULL)")
        db.execSQL(
            """
            CREATE TRIGGER article_search_update AFTER UPDATE OF title, body, url ON article
            WHEN NEW.title IS NOT OLD.title OR NEW.body IS NOT OLD.body OR NEW.url IS NOT OLD.url
            BEGIN
                DELETE FROM article_fts WHERE docid = NEW.id;
                DELETE FROM article_vector WHERE article_id = NEW.id;
                DELETE FROM article_archive WHERE article_id = NEW.id AND NEW.url IS NOT OLD.url;
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER article_search_delete AFTER DELETE ON article BEGIN
                DELETE FROM article_fts WHERE docid = OLD.id;
            END
            """.trimIndent(),
        )
    }

    private fun createArticleActions(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE article_action (
                id INTEGER PRIMARY KEY,
                feed_url TEXT NOT NULL,
                entry_key TEXT NOT NULL,
                article_url TEXT,
                kind TEXT NOT NULL,
                queue_id INTEGER,
                occurred_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createKeywordFilters(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE keyword_filter (
                id INTEGER PRIMARY KEY,
                feed_id INTEGER NOT NULL REFERENCES feed(id) ON DELETE CASCADE,
                queue_id INTEGER,
                mode TEXT NOT NULL CHECK(mode IN ('Include', 'Exclude')),
                term TEXT NOT NULL CHECK(term <> ''),
                FOREIGN KEY(feed_id, queue_id) REFERENCES feed_queue(feed_id, queue_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX keyword_filter_scope ON keyword_filter(feed_id, queue_id)")
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

    fun load(queueId: Long?, unreadOnly: Boolean, limit: Int, unassignedOnly: Boolean = false): LibrarySnapshot =
        readableDatabase.transaction {
            val queues = rawQuery(
                """
                SELECT q.id, q.name, COUNT(fq.feed_id)
                FROM queue q LEFT JOIN feed_queue fq ON fq.queue_id = q.id
                GROUP BY q.id ORDER BY q.id
                """.trimIndent(),
                null,
            ).use { cursor -> cursor.rows { FeedQueue(getLong(0), getString(1), getInt(2)) } }
            val args = articleListArgs(queueId)
            val listing = articleListing(articleListCondition(queueId, unreadOnly, unassignedOnly), args, limit)
            val hasUnread = listing.articles.any { !it.read } || (!unreadOnly && listing.hasMore && rawQuery(
                "SELECT 1 $ARTICLE_FROM WHERE ${articleListCondition(queueId, true, unassignedOnly)} LIMIT 1",
                args.toTypedArray(),
            ).use { it.moveToFirst() })
            LibrarySnapshot(subscriptions(this), queues, listing.articles, listing.hasMore, hasUnread)
        }

    fun subscriptions(): List<Subscription> = subscriptions(readableDatabase)

    fun feedArticles(feedId: Long, limit: Int, applyKeywordFilter: Boolean = true): ArticleListing {
        val filter = if (applyKeywordFilter) " AND ${keywordFilterCondition(forQueue = false)}" else ""
        return readableDatabase.articleListing("$ACTIVE_ARTICLE AND a.feed_id = ?$filter", listOf(feedId.toString()), limit)
    }

    fun history(limit: Int): ArticleListing =
        readableDatabase.articleListing("a.viewed_at IS NOT NULL", emptyList(), limit, "a.viewed_at DESC, a.id DESC")

    internal fun ratingHistory(limit: Int): RatingListing {
        val articles = readableDatabase.queryRatings(limit + 1).use { cursor -> cursor.rows { readRatedArticle() } }
        return RatingListing(articles.take(limit), articles.size > limit)
    }

    internal fun writeRatingHistory(output: Writer): Int = readableDatabase.transaction {
        val json = JsonWriter(output)
        var count = 0
        json.beginArray()
        queryRatings().use { cursor ->
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val entry = cursor.readRatedArticle()
                json.beginObject()
                json.name("feedUrl").value(entry.key.feedUrl)
                json.name("entryKey").value(entry.key.entryKey)
                json.name("feedTitle").value(entry.feedTitle)
                json.name("articleUrl").value(entry.url)
                json.name("title").value(entry.title)
                json.name("rating").value(entry.rating.name)
                json.name("ratedAt").value(Instant.ofEpochMilli(entry.ratedAt).toString())
                json.endObject()
                count++
            }
        }
        json.endArray()
        json.flush()
        count
    }

    private fun SQLiteDatabase.queryRatings(limit: Int? = null): Cursor = rawQuery(
        """
            SELECT r.feed_url, r.entry_key, a.id, COALESCE(f.title, r.feed_url),
                   COALESCE(NULLIF(a.title, ''), NULLIF(r.title, ''), a.url, r.article_url, '無題の記事'),
                   COALESCE(a.url, r.article_url), a.thumbnail_url, r.rating, r.rated_at
            FROM article_feedback r
            LEFT JOIN feed f ON f.url = r.feed_url
            LEFT JOIN article a ON a.feed_id = f.id AND a.entry_key = r.entry_key
            ORDER BY r.rated_at DESC, r.rowid DESC
        """.trimIndent() + if (limit != null) " LIMIT ?" else "",
        limit?.let { arrayOf(it.toString()) },
    )

    fun article(id: Long): Article? = readableDatabase.rawQuery(
        "${articleQuery("a.body", "(SELECT body FROM article_archive WHERE article_id = a.id)")} WHERE a.id = ?",
        arrayOf(id.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.readArticle() else null }

    internal fun archiveCandidates(now: Long, limit: Int): List<ArchiveCandidate> = readableDatabase.rawQuery(
        """
        SELECT a.id, a.url FROM article a LEFT JOIN article_archive t ON t.article_id = a.id
        WHERE a.url LIKE 'https://%' AND (t.article_id IS NULL OR t.retry_at <= ?)
        ORDER BY COALESCE(t.retry_at, 0), a.id DESC LIMIT ?
        """.trimIndent(),
        arrayOf(now.toString(), limit.toString()),
    ).use { it.rows { ArchiveCandidate(getLong(0), getString(1)) } }

    internal fun saveArchive(candidate: ArchiveCandidate, body: String?, retryAt: Long): Boolean = writableDatabase.transaction {
        val args = arrayOf(candidate.id.toString(), candidate.url)
        val current = rawQuery(
            "SELECT t.body, SUBSTR(a.body, 1, $MAX_ARCHIVE_CHARS) FROM article a LEFT JOIN article_archive t ON t.article_id = a.id WHERE a.id = ? AND a.url = ?",
            args,
        ).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: return@transaction false
        val candidateBody = body?.takeIf { it.length >= current.second.length * 0.8 }
        val next = candidateBody ?: current.first
        execSQL(
            """
            INSERT INTO article_archive(article_id, body, retry_at) VALUES (?, ?, ?)
            ON CONFLICT(article_id) DO UPDATE SET body = excluded.body, retry_at = excluded.retry_at
            """.trimIndent(),
            arrayOf<Any?>(candidate.id, next, retryAt),
        )
        if (next != current.first) {
            execSQL("DELETE FROM article_fts WHERE docid = ?", arrayOf(candidate.id))
            execSQL("DELETE FROM article_vector WHERE article_id = ?", arrayOf(candidate.id))
        }
        candidateBody != null
    }

    internal fun indexArticles(limit: Int): Int = writableDatabase.transaction {
        val documents = rawQuery(
            """
            SELECT a.id, a.title, SUBSTR(COALESCE(t.body, a.body), 1, $MAX_ARCHIVE_CHARS)
            FROM article a LEFT JOIN article_archive t ON t.article_id = a.id
            LEFT JOIN article_fts x ON x.docid = a.id WHERE x.docid IS NULL ORDER BY a.id DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { it.rows { SearchDocument(getLong(0), getString(1), getString(2)) } }
        val insert = compileStatement("INSERT INTO article_fts(docid, title, body) VALUES (?, ?, ?)")
        insert.use {
            for (document in documents) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                it.bindLong(1, document.id)
                it.bindString(2, japaneseIndexTokens(document.title))
                it.bindString(3, japaneseIndexTokens(document.storedBody))
                it.executeInsert()
            }
        }
        documents.size
    }

    internal fun lexicalArticleIds(words: List<String>, limit: Int): List<Long> = readableDatabase.rawQuery(
        """
        SELECT a.id FROM article_fts JOIN article a ON a.id = article_fts.docid
        WHERE article_fts MATCH ?
        ORDER BY (instr(lower(a.title), ?) > 0) DESC, COALESCE(a.published, a.discovered) DESC, a.id DESC LIMIT ?
        """.trimIndent(),
        arrayOf(japaneseMatchQuery(words), words.first(), limit.toString()),
    ).use { it.rows { getLong(0) } }

    internal fun searchArticles(ids: List<Long>, term: String): List<Article> {
        val fullBody = "COALESCE((SELECT body FROM article_archive WHERE article_id = a.id), a.body)"
        val preview = "SUBSTR($fullBody, MAX(1, instr(lower($fullBody), ?) - 32), 240)"
        return ids.chunked(200).flatMap { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            val articles = readableDatabase.rawQuery(
                "${articleQuery(preview)} WHERE a.id IN ($placeholders)", (listOf(term) + batch.map(Long::toString)).toTypedArray(),
            ).use { cursor -> cursor.rows { readArticle() }.associateBy { it.id } }
            batch.mapNotNull(articles::get)
        }
    }

    internal fun documentsForVectors(limit: Int): List<SearchDocument> = readableDatabase.rawQuery(
        """
        SELECT a.id, a.title, SUBSTR(COALESCE(t.body, a.body), 1, 4000)
        FROM article a LEFT JOIN article_archive t ON t.article_id = a.id
        LEFT JOIN article_vector v ON v.article_id = a.id WHERE v.article_id IS NULL
        ORDER BY a.id DESC LIMIT ?
        """.trimIndent(),
        arrayOf(limit.toString()),
    ).use { it.rows { SearchDocument(getLong(0), getString(1), getString(2)) } }

    internal fun saveVector(document: SearchDocument, vector: FloatArray) = writableDatabase.execSQL(
        """
        INSERT OR IGNORE INTO article_vector(article_id, embedding)
        SELECT a.id, ? FROM article a LEFT JOIN article_archive t ON t.article_id = a.id
        WHERE a.id = ? AND a.title = ? AND SUBSTR(COALESCE(t.body, a.body), 1, 4000) = ?
        """.trimIndent(),
        arrayOf<Any>(encodeEmbedding(vector), document.id, document.title, document.storedBody),
    )

    internal fun semanticArticleIds(vector: FloatArray, limit: Int): List<Long> {
        val nearest = PriorityQueue<Pair<Long, Float>>(compareBy({ it.second }, { it.first }))
        readableDatabase.rawQuery("SELECT article_id, embedding FROM article_vector", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val similarity = embeddingSimilarity(vector, cursor.getBlob(1))
                if (similarity >= 0.40f && (nearest.size < limit || similarity > (nearest.peek()?.second ?: -1f))) {
                    if (nearest.size == limit) nearest.poll()
                    nearest.add(cursor.getLong(0) to similarity)
                }
            }
        }
        return nearest.sortedWith(compareByDescending<Pair<Long, Float>> { it.second }.thenByDescending { it.first }).map { it.first }
    }

    internal fun semanticEnabled(): Boolean = readableDatabase.rawQuery(
        "SELECT semantic_enabled FROM search_options WHERE id = 1", null,
    ).use { it.moveToFirst() && it.getInt(0) != 0 }

    internal fun setSemanticEnabled(enabled: Boolean) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO search_options(id, semantic_enabled) VALUES (1, ?)", arrayOf(if (enabled) 1 else 0))
    }

    internal fun searchStats(modelBytes: Long): SearchStats = readableDatabase.transaction {
        fun count(query: String): Long = rawQuery(query, null).use {
            it.moveToFirst()
            it.getLong(0)
        }
        fun pageBytes(filter: String): Long? = try {
            count("SELECT COALESCE(SUM(pgsize), 0) FROM dbstat WHERE $filter")
        } catch (_: SQLiteException) {
            null
        }
        SearchStats(
            articles = count("SELECT COUNT(*) FROM article"),
            archived = count("SELECT COUNT(body) FROM article_archive"),
            indexed = count("SELECT COUNT(*) FROM article_fts"),
            archiveBytes = count("SELECT COALESCE(SUM(length(CAST(body AS BLOB))), 0) FROM article_archive"),
            lexicalBytes = pageBytes("name GLOB 'article_fts_*'"),
            vectors = count("SELECT COUNT(*) FROM article_vector"),
            vectorBytes = pageBytes("name = 'article_vector'"),
            modelBytes = modelBytes,
            semanticEnabled = semanticEnabled(),
        )
    }

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
        val filtersByFeed = db.rawQuery("SELECT feed_id, queue_id, mode, term FROM keyword_filter ORDER BY id", null)
            .use { cursor ->
                cursor.rows { KeywordTerm(getLong(0), nullableLong(1), KeywordFilterMode.valueOf(getString(2)), getString(3)) }
            }.groupBy { it.feedId }
        return db.rawQuery(
            """
            SELECT f.id, f.url, f.title, f.etag, f.modified, f.refreshed_at, f.error, f.html_item_selector,
                   f.home_url, f.is_favorite,
                   (SELECT COALESCE(a.published, a.discovered) FROM article a WHERE a.feed_id = f.id
                    ORDER BY COALESCE(a.published, a.discovered) DESC LIMIT 1), f.html_watch, f.notifications
            FROM feed f ORDER BY f.title COLLATE NOCASE
            """.trimIndent(),
            null,
        ).use { cursor ->
            cursor.rows {
                val id = getLong(0)
                val filters = filtersByFeed[id].orEmpty().groupBy { it.queueId }.mapValues { (_, terms) ->
                    KeywordFilter(terms.first().mode, terms.map { it.term })
                }
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
                    keywordFilter = filters[null],
                    queueKeywordFilters = filters.mapNotNull { (queueId, rule) -> queueId?.let { it to rule } }.toMap(),
                    htmlWatch = getString(11),
                    notifications = FeedNotificationMode.fromValue(getInt(12)),
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

    internal fun opmlSubscriptions(): OpmlContents = readableDatabase.transaction {
        val queues = rawQuery("SELECT id, name FROM queue ORDER BY id", null).use { cursor ->
            cursor.rows { getLong(0) to getString(1) }.toMap()
        }
        val feeds = subscriptions(this).map { feed ->
            OpmlFeed(
                feed.url, feed.title, feed.homeUrl, feed.htmlItemSelector, feed.htmlWatch, feed.isFavorite, feed.keywordFilter,
                feed.queueIds.mapNotNull { id -> queues[id]?.let { it to feed.queueKeywordFilters[id] } }.toMap(),
                feed.notifications,
            )
        }
        OpmlContents(feeds, 0, 0, queues.values.toList())
    }

    internal fun importSubscriptions(contents: OpmlContents): FeedImportResult = writableDatabase.transaction {
        val queueIds = mutableMapOf<String, Long>()
        var createdQueues = 0
        fun queueId(name: String): Long = queueIds.getOrPut(name) {
            val inserted = insertWithOnConflict(
                "queue", null, ContentValues().apply { put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (inserted != -1L) {
                createdQueues++
                inserted
            } else {
                rawQuery("SELECT id FROM queue WHERE name = ?", arrayOf(name)).use {
                    check(it.moveToFirst())
                    it.getLong(0)
                }
            }
        }
        contents.queues.forEach { queueId(it) }
        val feeds = contents.feeds.mapNotNull { feed ->
            val id = insertFeed(
                feed.url, feed.title, feed.homeUrl, feed.htmlItemSelector, feed.htmlWatch, feed.isFavorite, feed.notifications,
            )
            if (id == -1L) return@mapNotNull null
            val membership = feed.queueFilters.entries.associate { (name, rule) -> queueId(name) to rule }
            membership.keys.forEach { addMembership(id, it) }
            if (feed.keywordFilter != null) {
                insertKeywordFilter(id, null, feed.keywordFilter)
            } else {
                membership.forEach { (queueId, rule) -> if (rule != null) insertKeywordFilter(id, queueId, rule) }
            }
            id
        }.toSet()
        FeedImportResult(feeds, contents.duplicateCount, contents.unsupportedCount, createdQueues)
    }

    private fun SQLiteDatabase.insertFeed(
        url: String,
        title: String,
        homeUrl: String? = null,
        htmlItemSelector: String? = null,
        htmlWatch: String? = null,
        isFavorite: Boolean = false,
        notifications: FeedNotificationMode = FeedNotificationMode.Default,
    ): Long {
        val values = ContentValues().apply {
            put("url", url)
            put("title", title)
            put("home_url", homeUrl)
            put("html_item_selector", htmlItemSelector)
            put("html_watch", htmlWatch)
            put("is_favorite", if (isFavorite) 1 else 0)
            put("notifications", notifications.value)
        }
        return insertWithOnConflict("feed", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    internal fun recordRefresh(
        feedId: Long,
        document: FeedDocument?,
        expected: Subscription? = null,
        error: String? = null,
        notificationDefault: Boolean? = null,
    ): FeedRefreshResult = writableDatabase.transaction {
        // A preview can be saved while an older refresh is still fetching the page.
        val canNotify = rawQuery(
            "SELECT etag, modified, html_item_selector, html_watch, refreshed_at, notifications FROM feed WHERE id = ?",
            arrayOf(feedId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@transaction FeedRefreshResult(false)
            val refreshedAt = cursor.nullableLong(4)
            if (expected != null && (
                    cursor.getString(0) != expected.etag || cursor.getString(1) != expected.modified ||
                        cursor.getString(2) != expected.htmlItemSelector || cursor.getString(3) != expected.htmlWatch ||
                        refreshedAt != expected.refreshedAt
                )
            ) return@transaction FeedRefreshResult(false)
            notificationDefault != null && refreshedAt != null && FeedNotificationMode.fromValue(cursor.getInt(5))
                .enabled(notificationDefault)
        }
        // New rows have IDs above this boundary, even if their publication dates are older.
        val previousArticleId = if (canNotify && document != null && error == null && document.articles.isNotEmpty()) {
            rawQuery("SELECT COALESCE(MAX(id), 0) FROM article", null).use {
                check(it.moveToFirst())
                it.getLong(0)
            }
        } else null
        val now = System.currentTimeMillis()
        when {
            error != null -> execSQL("UPDATE feed SET error = ? WHERE id = ?", arrayOf<Any>(error, feedId))
            document != null -> saveDocument(feedId, document, now)
            else -> execSQL("UPDATE feed SET refreshed_at = ?, error = NULL WHERE id = ?", arrayOf(now, feedId))
        }
        val newArticles = previousArticleId?.let { newFeedArticles(feedId, it) }
        FeedRefreshResult(true, newArticles)
    }

    internal fun setFeedNotifications(feedId: Long, mode: FeedNotificationMode): Unit {
        writableDatabase.execSQL("UPDATE feed SET notifications = ? WHERE id = ?", arrayOf<Any>(mode.value, feedId))
    }

    private fun SQLiteDatabase.newFeedArticles(feedId: Long, afterId: Long): NewFeedArticles? = rawQuery(
        """
        SELECT f.title, COALESCE(NULLIF(a.title, ''), a.url, '無題の記事'), COUNT(*) OVER()
        $ARTICLE_FROM
        WHERE a.id > ? AND a.feed_id = ? AND $ACTIVE_ARTICLE AND ${keywordFilterCondition(forQueue = false)}
        ORDER BY COALESCE(a.published, a.discovered) DESC, a.id DESC LIMIT 1
        """.trimIndent(),
        arrayOf(afterId.toString(), feedId.toString()),
    ).use { cursor ->
        if (cursor.moveToFirst()) NewFeedArticles(feedId, cursor.getString(0), cursor.getString(1), cursor.getInt(2)) else null
    }

    internal fun recentWebsiteKeys(feedId: Long): Set<String> = readableDatabase.rawQuery(
        "SELECT entry_key FROM article WHERE feed_id = ? ORDER BY id DESC LIMIT 500", arrayOf(feedId.toString()),
    ).use { cursor -> cursor.rows { getString(0) }.toSet() }

    internal fun updateWebsiteScope(feedId: Long, document: FeedDocument): Boolean = writableDatabase.transaction {
        require(document.htmlItemSelector != null && document.htmlWatch != null)
        val exists = rawQuery(
            "SELECT 1 FROM feed WHERE id = ? AND html_item_selector IS NOT NULL", arrayOf(feedId.toString()),
        ).use { it.moveToFirst() }
        if (exists) saveDocument(feedId, document, System.currentTimeMillis())
        exists
    }

    fun setMembership(
        feedId: Long,
        queueIds: Set<Long>,
        keywordFilters: Map<Long, KeywordFilter>? = null,
    ): Unit = writableDatabase.transaction {
        val args = arrayOf(feedId.toString())
        val existing = membershipIds(feedId)
        (existing - queueIds).forEach { queueId ->
            delete("feed_queue", "feed_id = ? AND queue_id = ?", arrayOf(feedId.toString(), queueId.toString()))
        }
        (queueIds - existing).forEach { addMembership(feedId, it) }
        if (keywordFilters != null) {
            val members = membershipIds(feedId)
            val selectedFilters = keywordFilters.filterKeys { it in members }
            val scope = if (selectedFilters.isEmpty()) "feed_id = ? AND queue_id IS NOT NULL" else "feed_id = ?"
            delete("keyword_filter", scope, args)
            selectedFilters.forEach { (queueId, rule) -> insertKeywordFilter(feedId, queueId, rule) }
        }
    }

    fun setFeedKeywordFilter(feedId: Long, rule: KeywordFilter?): Unit = writableDatabase.transaction {
        val scope = if (rule == null) "feed_id = ? AND queue_id IS NULL" else "feed_id = ?"
        delete("keyword_filter", scope, arrayOf(feedId.toString()))
        if (rule != null) insertKeywordFilter(feedId, null, rule)
    }

    private fun SQLiteDatabase.membershipIds(feedId: Long): Set<Long> =
        rawQuery("SELECT queue_id FROM feed_queue WHERE feed_id = ?", arrayOf(feedId.toString()))
            .use { cursor -> cursor.rows { getLong(0) }.toSet() }

    private fun SQLiteDatabase.insertKeywordFilter(feedId: Long, queueId: Long?, rule: KeywordFilter) {
        rule.keywords.distinct().forEach { term ->
            insertOrThrow("keyword_filter", null, ContentValues().apply {
                put("feed_id", feedId)
                if (queueId != null) put("queue_id", queueId) else putNull("queue_id")
                put("mode", rule.mode.name)
                put("term", term)
            })
        }
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

    fun markAllRead(queueId: Long?, unassignedOnly: Boolean = false): Int = writableDatabase.update(
        "article",
        ContentValues(1).apply { put("read", 1) },
        "id IN (SELECT a.id $ARTICLE_FROM WHERE ${articleListCondition(queueId, true, unassignedOnly)})",
        articleListArgs(queueId).toTypedArray(),
    )

    fun recordView(id: Long, viewedAt: Long = System.currentTimeMillis()): Unit =
        writableDatabase.execSQL("UPDATE article SET read = 1, viewed_at = ? WHERE id = ?", arrayOf(viewedAt, id))

    fun recordOriginalOpen(id: Long, queueId: Long? = null, openedAt: Long = System.currentTimeMillis()): Unit =
        writableDatabase.transaction {
            insertArticleActions(ArticleAction.OriginalOpened, queueId, "a.id = ?", listOf(id.toString()), openedAt)
            execSQL(
                """
                UPDATE article SET read = 1, dismissed = 1,
                    viewed_at = MAX(COALESCE(viewed_at, ?), ?),
                    original_opened_at = MAX(COALESCE(original_opened_at, ?), ?)
                WHERE id = ?
                """.trimIndent(),
                arrayOf(openedAt, openedAt, openedAt, openedAt, id),
            )
        }

    internal fun recordArchivedOriginalOpen(key: RatingKey, url: String): Unit = writableDatabase.execSQL(
        "INSERT INTO article_action(feed_url, entry_key, article_url, kind, occurred_at) VALUES (?, ?, ?, ?, ?)",
        arrayOf<Any>(key.feedUrl, key.entryKey, url, ArticleAction.OriginalOpened.name, System.currentTimeMillis()),
    )

    fun dismissArticle(id: Long, rating: ArticleRating?, queueId: Long? = null): Unit = writableDatabase.transaction {
        if (rating == null) {
            dismissArticles(ArticleAction.RemovedIndividually, queueId, "$ACTIVE_ARTICLE AND a.id = ?", listOf(id.toString()))
            return@transaction
        }
        val dismissed = ContentValues().apply { put("dismissed", 1) }
        val updated = update(
            "article", dismissed, "id = ? AND (dismissed = 0 OR original_opened_at IS NOT NULL)", arrayOf(id.toString()),
        )
        if (updated == 0) return@transaction
        execSQL(
            """
            INSERT OR IGNORE INTO article_feedback(feed_url, entry_key, article_url, title, body, published_at, rating, rated_at)
            SELECT f.url, a.entry_key, a.url, a.title, a.body, a.published, ?, ?
            FROM article a JOIN feed f ON f.id = a.feed_id WHERE a.id = ?
            """.trimIndent(),
            arrayOf<Any>(rating.value, System.currentTimeMillis(), id),
        )
    }

    fun dismissQueueArticles(queueId: Long, unreadOnly: Boolean): Int = writableDatabase.transaction {
        dismissArticles(
            ArticleAction.RemovedFromQueueInBulk, queueId,
            articleListCondition(queueId, unreadOnly, unassignedOnly = false), articleListArgs(queueId),
        )
    }

    fun dismissFeedArticles(feedId: Long): Int = writableDatabase.transaction {
        dismissArticles(ArticleAction.RemovedFromFeedInBulk, null, "$ACTIVE_ARTICLE AND a.feed_id = ?", listOf(feedId.toString()))
    }

    private fun SQLiteDatabase.dismissArticles(
        action: ArticleAction, queueId: Long?, condition: String, args: List<String>,
    ): Int {
        insertArticleActions(action, queueId, condition, args, System.currentTimeMillis())
        return update(
            "article", ContentValues().apply { put("dismissed", 1) },
            "id IN (SELECT a.id $ARTICLE_FROM WHERE $condition)", args.toTypedArray(),
        )
    }

    private fun SQLiteDatabase.insertArticleActions(
        action: ArticleAction, queueId: Long?, condition: String, args: List<String>, occurredAt: Long,
    ) {
        execSQL(
            """
            INSERT INTO article_action(feed_url, entry_key, article_url, kind, queue_id, occurred_at)
            SELECT f.url, a.entry_key, a.url, ?, ?, ?
            $ARTICLE_FROM WHERE $condition
            """.trimIndent(),
            arrayOf<Any?>(action.name, queueId, occurredAt, *args.toTypedArray()),
        )
    }

    internal fun changeRating(key: RatingKey, rating: ArticleRating?): Unit = writableDatabase.transaction {
        val args = arrayOf(key.feedUrl, key.entryKey)
        if (rating != null) {
            val values = ContentValues().apply { put("rating", rating.value) }
            update("article_feedback", values, "feed_url = ? AND entry_key = ?", args)
        } else if (delete("article_feedback", "feed_url = ? AND entry_key = ?", args) > 0) {
            execSQL(
                """
                UPDATE article SET dismissed = 0
                WHERE feed_id = (SELECT id FROM feed WHERE url = ?) AND entry_key = ? AND original_opened_at IS NULL
                """.trimIndent(),
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
            put("html_watch", document.htmlWatch)
            document.home?.let { put("home_url", it) }
            put("refreshed_at", now)
            putNull("error")
        }
        if (update("feed", metadata, "id = ?", arrayOf(feedId.toString())) == 0) return
        document.articles.asReversed().forEach { article ->
            execSQL(
                """
                INSERT INTO article(feed_id, entry_key, title, url, body, published, discovered, thumbnail_url,
                                    source_updated_at, source_published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(feed_id, entry_key) DO UPDATE SET
                  title = excluded.title, url = excluded.url, body = excluded.body,
                  thumbnail_url = excluded.thumbnail_url,
                  published = COALESCE(excluded.published, article.published),
                  source_updated_at = COALESCE(excluded.source_updated_at, article.source_updated_at),
                  source_published_at = COALESCE(excluded.source_published_at, article.source_published_at),
                  change_detected_at = CASE
                    WHEN excluded.title IS NOT article.title OR excluded.body IS NOT article.body
                      OR excluded.url IS NOT article.url OR excluded.thumbnail_url IS NOT article.thumbnail_url
                    THEN MAX(excluded.discovered, COALESCE(article.change_detected_at, article.discovered))
                    ELSE article.change_detected_at END
                """.trimIndent(),
                arrayOf<Any?>(
                    feedId, article.key, article.title.orEmpty(), article.url, article.body.orEmpty(),
                    article.published, document.observedAt ?: now, article.thumbnailUrl,
                    article.updatedAt, article.publishedTime,
                ),
            )
        }
    }
}

private inline fun <T> Cursor.rows(readRow: Cursor.() -> T): List<T> =
    buildList { while (moveToNext()) add(readRow()) }

private fun Cursor.nullableLong(column: Int): Long? = if (isNull(column)) null else getLong(column)

private fun Cursor.readRatedArticle(): RatedArticle =
    RatedArticle(
        RatingKey(getString(0), getString(1)), nullableLong(2), getString(3), getString(4),
        getString(5), getString(6), ArticleRating.entries.first { it.value == getInt(7) }, getLong(8),
    )

private fun Cursor.readArticle(): Article =
    Article(
        id = getLong(0), feedTitle = getString(1), title = getString(2), url = getString(3),
        body = getString(4), published = nullableLong(5), read = getInt(6) != 0, thumbnailUrl = getString(7),
        feedId = getLong(8), viewedAt = nullableLong(9), dismissed = getInt(10) != 0,
        rating = ArticleRating.entries.firstOrNull { it.value == getInt(11) },
        updatedAt = nullableLong(12), publishedTime = nullableLong(13),
        changeDetectedAt = nullableLong(14), discoveredAt = getLong(15),
        originalOpenedAt = nullableLong(16),
        archivedBody = getString(17),
    )

private const val ACTIVE_ARTICLE = "a.dismissed = 0 AND r.rating IS NULL"
private const val FEED_ARTICLE_INDEX =
    "CREATE INDEX article_feed_newest ON article(feed_id, COALESCE(published, discovered) DESC)"

private data class KeywordTerm(val feedId: Long, val queueId: Long?, val mode: KeywordFilterMode, val term: String)

private fun articleListCondition(queueId: Long?, unreadOnly: Boolean, unassignedOnly: Boolean): String = buildList {
    add(ACTIVE_ARTICLE)
    if (queueId != null) {
        add("EXISTS (SELECT 1 FROM feed_queue fq WHERE fq.feed_id = a.feed_id AND fq.queue_id = ?)")
    } else if (unassignedOnly) {
        add("NOT EXISTS (SELECT 1 FROM feed_queue fq WHERE fq.feed_id = a.feed_id)")
    }
    add(keywordFilterCondition(forQueue = queueId != null))
    if (unreadOnly) add("a.read = 0")
}.joinToString(" AND ")

private fun articleListArgs(queueId: Long?): List<String> =
    queueId?.let { listOf(it.toString(), it.toString()) }.orEmpty()

private fun keywordFilterCondition(forQueue: Boolean): String {
    val scope = if (forQueue) "(k.queue_id IS NULL OR k.queue_id = ?)" else "k.queue_id IS NULL"
    // All terms in the applicable scope have the same mode. A missing rule allows the article.
    return """
        COALESCE((
            SELECT (MIN(k.mode) = 'Include') = MAX(
                instr(lower(a.title), lower(k.term)) > 0 OR instr(lower(a.body), lower(k.term)) > 0
            )
            FROM keyword_filter k WHERE k.feed_id = a.feed_id AND $scope
        ), 1)
    """.trimIndent()
}

private const val ARTICLE_FROM = """
    FROM article a
    JOIN feed f ON f.id = a.feed_id
    LEFT JOIN article_feedback r ON r.feed_url = f.url AND r.entry_key = a.entry_key
"""

private fun articleQuery(body: String, archivedBody: String = "NULL"): String = """
    SELECT a.id, f.title, NULLIF(a.title, ''), a.url, NULLIF($body, ''), a.published, a.read,
           a.thumbnail_url, a.feed_id, a.viewed_at, a.dismissed OR r.rating IS NOT NULL, r.rating,
           a.source_updated_at, a.source_published_at, a.change_detected_at, a.discovered, a.original_opened_at, $archivedBody
    $ARTICLE_FROM
""".trimIndent()

internal inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransactionNonExclusive()
    return try {
        block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
