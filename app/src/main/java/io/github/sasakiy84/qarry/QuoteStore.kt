package io.github.sasakiy84.qarry

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class QuoteLearningState(val weights: FloatArray, val answers: Int, val lastAnswerId: String?)

internal class QuoteStore(private val store: FeedStore) {
    fun state(): QuoteLearningState = store.readableDatabase.transaction {
        val count = rawQuery("SELECT COUNT(*) FROM quote_feedback WHERE $ACTIVE_ANSWER", featureArgs).use {
            check(it.moveToFirst())
            it.getInt(0)
        }
        QuoteLearningState(weights(), count, latestAnswer()?.first)
    }

    fun weights(): FloatArray = store.readableDatabase.weights()

    fun nextSource(beforeArticle: Long?): QuoteSource? = store.readableDatabase.rawQuery(
        """
        SELECT a.id, COALESCE(NULLIF(a.title, ''), a.url, '無題の記事'), b.body
        FROM article_archive b JOIN article a ON a.id = b.article_id
        WHERE b.body IS NOT NULL AND a.id < ? AND NOT EXISTS (
            SELECT 1 FROM quote_feedback q
            WHERE q.article_id = a.id AND $FEATURE_SCOPE AND q.undone_at IS NULL
        )
        ORDER BY a.id DESC LIMIT 1
        """.trimIndent(),
        arrayOf((beforeArticle ?: Long.MAX_VALUE).toString(), *featureArgs),
    ).use { cursor ->
        if (cursor.moveToFirst()) QuoteSource(cursor.getLong(0), cursor.getString(1), cursor.getString(2)) else null
    }

    fun record(comparison: QuoteComparison, choice: Int?, now: Long = System.currentTimeMillis()): Boolean =
        store.writableDatabase.transaction {
            require(choice == null || choice in 0..1)
            val currentBody = rawQuery(
                "SELECT body FROM article_archive WHERE article_id = ?", arrayOf(comparison.articleId.toString()),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (currentBody == null || quoteBodyVersion(currentBody) != comparison.bodyVersion) return@transaction false
            val (top, bottom) = comparison.displayed
            val previous = if (choice != null) weights() else null
            val inserted = insertWithOnConflict("quote_feedback", null, featureValues().apply {
                put("comparison_id", comparison.id)
                put("article_id", comparison.articleId)
                put("article_title", comparison.title)
                put("body_version", comparison.bodyVersion)
                put("top_start", top.span.start)
                put("top_end", top.span.end)
                put("top_text", top.span.text)
                put("bottom_start", bottom.span.start)
                put("bottom_end", bottom.span.end)
                put("bottom_text", bottom.span.text)
                put("choice", choice)
                put("answered_at", now)
                put("previous_weights", previous?.toBlob())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted == -1L) return@transaction false
            if (choice != null && previous != null) {
                saveWeights(updateQuoteWeights(previous, comparison.displayed[choice], comparison.displayed[1 - choice]))
            }
            true
        }

    fun undo(comparisonId: String, now: Long = System.currentTimeMillis()): Boolean = store.writableDatabase.transaction {
        val (latestId, previous) = latestAnswer() ?: return@transaction false
        if (latestId != comparisonId) return@transaction false
        saveWeights(previous)
        execSQL("UPDATE quote_feedback SET undone_at = ? WHERE comparison_id = ?", arrayOf<Any>(now, comparisonId))
        true
    }

    private fun SQLiteDatabase.latestAnswer(): Pair<String, FloatArray>? = rawQuery(
        "SELECT comparison_id, previous_weights FROM quote_feedback WHERE $ACTIVE_ANSWER ORDER BY rowid DESC LIMIT 1",
        featureArgs,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getBlob(1).toQuoteWeights() else null }

    private fun SQLiteDatabase.weights(): FloatArray = rawQuery(
        "SELECT weights FROM quote_model WHERE $FEATURE_SCOPE", featureArgs,
    ).use { if (it.moveToFirst()) it.getBlob(0).toQuoteWeights() else FloatArray(QUOTE_FEATURE_SIZE) }

    private fun SQLiteDatabase.saveWeights(weights: FloatArray) {
        check(insertWithOnConflict("quote_model", null, featureValues().apply {
            put("weights", weights.toBlob())
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
}

internal fun createQuoteTables(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE quote_model (
            feature_version INTEGER NOT NULL,
            encoder TEXT NOT NULL,
            dimensions INTEGER NOT NULL,
            weights BLOB NOT NULL,
            PRIMARY KEY(feature_version, encoder, dimensions)
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE quote_feedback (
            comparison_id TEXT NOT NULL UNIQUE,
            article_id INTEGER NOT NULL,
            article_title TEXT NOT NULL,
            body_version TEXT NOT NULL,
            top_start INTEGER NOT NULL,
            top_end INTEGER NOT NULL,
            top_text TEXT NOT NULL,
            bottom_start INTEGER NOT NULL,
            bottom_end INTEGER NOT NULL,
            bottom_text TEXT NOT NULL,
            choice INTEGER CHECK(choice IN (0, 1)),
            answered_at INTEGER NOT NULL,
            undone_at INTEGER,
            feature_version INTEGER NOT NULL,
            encoder TEXT NOT NULL,
            dimensions INTEGER NOT NULL,
            previous_weights BLOB
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX quote_feedback_article ON quote_feedback(article_id, feature_version, encoder, dimensions)")
}

private const val FEATURE_SCOPE = "feature_version = ? AND encoder = ? AND dimensions = ?"
private const val ACTIVE_ANSWER = "$FEATURE_SCOPE AND choice IS NOT NULL AND undone_at IS NULL"
private val featureArgs = arrayOf(QUOTE_FEATURE_VERSION.toString(), QUOTE_ENCODER_ID, QUOTE_FEATURE_SIZE.toString())
private fun featureValues(): ContentValues = ContentValues().apply {
    put("feature_version", QUOTE_FEATURE_VERSION)
    put("encoder", QUOTE_ENCODER_ID)
    put("dimensions", QUOTE_FEATURE_SIZE)
}

private fun FloatArray.toBlob(): ByteArray = ByteBuffer.allocate(size * Float.SIZE_BYTES)
    .order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(this) }.array()

private fun ByteArray.toQuoteWeights(): FloatArray {
    check(size == QUOTE_FEATURE_SIZE * Float.SIZE_BYTES)
    return FloatArray(QUOTE_FEATURE_SIZE).also { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
}
