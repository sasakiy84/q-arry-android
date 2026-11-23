package io.github.sasakiy84.qarry

import org.junit.Assert.*
import org.junit.Test

class QuoteRankingTest {
    @Test
    fun extractsWholeSentencesAtTheirOriginalUtf16Offsets() {
        val sentences = listOf(
            "研究者は🧪の実験で、薬剤が10％未満なら効果が出ないことを確認した。",
            "The cafe\u0301 did not reopen after 6 p.m. unless the storm had passed.",
            "比較する条件はすべての参加者で同じだった。",
        )
        val body = "見出し\n\n  " + sentences.joinToString("\n  ") + "\n続く言葉はまだ"
        val spans = quoteSpans(body)
        assertEquals(sentences, spans.map { it.text })
        for (span in spans) assertEquals(span.text, body.substring(span.start, span.end))
    }

    @Test
    fun repeatedPairwisePreferencesCanReverseTheInitialOrder() {
        val spans = listOf(QuoteSpan(0, 10, "First"), QuoteSpan(10, 20, "Second"))
        val initial = QuoteCandidate(spans[0], FloatArray(QUOTE_FEATURE_SIZE).apply { this[0] = 1f }, 0.5f)
        val preferred = QuoteCandidate(spans[1], FloatArray(QUOTE_FEATURE_SIZE).apply { this[1] = 1f }, 0.25f)
        var weights = FloatArray(QUOTE_FEATURE_SIZE)
        assertTrue(initial.score(weights) > preferred.score(weights))
        repeat(8) { weights = updateQuoteWeights(weights, preferred, initial) }
        assertTrue(preferred.score(weights) > initial.score(weights))
    }

    @Test
    fun tokenizesEnglishAndJapaneseWithTheModelsWordPieceRules() {
        val tokenizer = QuoteWordPiece(mapOf("cafe" to 8, "!" to 9, "日" to 10, "本" to 11, "てした" to 12, "play" to 13, "##ing" to 14))
        assertArrayEquals(longArrayOf(8, 9, 10, 11, 12, 13, 14, 0), tokenizer.encode("Café! 日本でした PLAYING 🧪"))
    }
}
