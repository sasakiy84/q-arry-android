package io.github.sasakiy84.qarry

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuoteStoreTest {
    @Test
    fun persistsAnAnswerOnceAndRestoresItsExactPreviousWeightsOnUndo() {
        val context = RuntimeEnvironment.getApplication()
        val firstBody = "書庫に新しい資料が届いた。資料は次の調査で参照する予定だ。"
        val secondBody = "A new train now stops at the station. The last departure leaves just before midnight."
        var answerId = ""
        var trained = FloatArray(0)
        FeedStore(context).use { store ->
            val otherId = archiveQuoteFixture(store, "older", secondBody)
            val articleId = archiveQuoteFixture(store, "newer", firstBody)
            val quotes = QuoteStore(store)
            val source = checkNotNull(quotes.nextSource(null))
            assertEquals(articleId, source.articleId)
            val comparison = exampleComparison(source, "answer")
            answerId = comparison.id
            assertTrue(quotes.record(comparison, 1, now = 100L))
            trained = quotes.weights()
            assertTrue(trained.any { it != 0f })
            assertFalse(quotes.record(comparison, 0, now = 200L))
            val other = checkNotNull(quotes.nextSource(null))
            assertEquals(otherId, other.articleId)
            assertTrue(quotes.record(exampleComparison(other, "skip"), null, now = 300L))
            assertArrayEquals(trained, quotes.weights(), 0f)
            assertNull(quotes.nextSource(null))
            store.readableDatabase.rawQuery(
                """
                SELECT body_version, top_start, top_end, top_text, bottom_start, bottom_end, bottom_text,
                       choice, answered_at, encoder, feature_version, dimensions FROM quote_feedback WHERE comparison_id = ?
                """.trimIndent(),
                arrayOf(answerId),
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(quoteBodyVersion(firstBody), it.getString(0))
                assertEquals(firstBody.substring(it.getInt(1), it.getInt(2)), it.getString(3))
                assertEquals(firstBody.substring(it.getInt(4), it.getInt(5)), it.getString(6))
                assertEquals(1, it.getInt(7))
                assertEquals(100L, it.getLong(8))
                assertEquals(QUOTE_ENCODER_ID, it.getString(9))
                assertEquals(QUOTE_FEATURE_VERSION, it.getInt(10))
                assertEquals(QUOTE_FEATURE_SIZE, it.getInt(11))
            }
        }
        FeedStore(context).use { store ->
            val quotes = QuoteStore(store)
            val restored = quotes.state()
            assertEquals(1, restored.answers)
            assertEquals(answerId, restored.lastAnswerId)
            assertArrayEquals(trained, restored.weights, 0f)
            assertTrue(quotes.undo(answerId))
            assertFalse(quotes.undo(answerId))
            assertEquals(0, quotes.state().answers)
            assertArrayEquals(FloatArray(QUOTE_FEATURE_SIZE), quotes.weights(), 0f)
            assertEquals(firstBody, quotes.nextSource(null)?.body)
        }
    }

    @Test
    fun rejectsAnAnswerWhenTheArchivedBodyHasChanged() {
        FeedStore(RuntimeEnvironment.getApplication()).use { store ->
            val articleId = archiveQuoteFixture(store, "changed", "The former shop is now a museum. It opens every morning.")
            val quotes = QuoteStore(store)
            val comparison = exampleComparison(checkNotNull(quotes.nextSource(null)), "stale")
            store.saveArchive(
                ArchiveCandidate(articleId, "http://test.example/changed"),
                "The museum stays open until seven. The cafe closes in the afternoon.", Long.MAX_VALUE,
            )
            assertFalse(quotes.record(comparison, 0))
            assertEquals(0, quotes.state().answers)
        }
    }

    private fun exampleComparison(source: QuoteSource, id: String): QuoteComparison {
        val split = source.body.indexOfFirst { it == '.' || it == '。' } + 1
        val spans = listOf(
            QuoteSpan(0, split, source.body.substring(0, split)),
            QuoteSpan(split, source.body.length, source.body.substring(split)),
        )
        val candidates = spans.mapIndexed { index, span ->
            QuoteCandidate(span, FloatArray(QUOTE_FEATURE_SIZE).apply { this[index] = 1f }, 0.25f)
        }
        return QuoteComparison(id, source.articleId, source.title, quoteBodyVersion(source.body), candidates)
    }
}

internal fun archiveQuoteFixture(store: FeedStore, key: String, body: String, title: String = key): Long {
    val url = "http://test.example/$key"
    val feed = store.subscribe(
        FeedDocument("https://test.example/feed/$key", "フィード $key", null, listOf(FeedArticle(key, title, url, null, null))),
        null,
    )
    val article = store.feedArticles(feed, 1).articles.single()
    check(store.saveArchive(ArchiveCandidate(article.id, url), body, Long.MAX_VALUE))
    return article.id
}
