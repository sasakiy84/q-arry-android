package io.github.sasakiy84.qarry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import java.io.IOException

internal class QuoteLearning(private val store: QuoteStore, private val encoder: QuoteEncoder?) {
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()

    suspend fun state(): QuoteLearningState = runInterruptible(Dispatchers.IO, store::state)

    suspend fun nextSource(beforeArticle: Long?): QuoteSource? = runInterruptible(Dispatchers.IO) {
        store.nextSource(beforeArticle)
    }

    suspend fun rank(source: QuoteSource, minimum: Int): RankedQuotes = runInterruptible(Dispatchers.Default) {
        rankQuotes(source, encoder ?: throw IOException("Quote encoder unavailable"), minimum)
    }

    suspend fun forArticle(source: QuoteSource): List<QuoteSpan> {
        val ranked = rank(source, minimum = 1)
        if (ranked.candidates.isEmpty()) return emptyList()
        val weights = runInterruptible(Dispatchers.IO, store::weights)
        return ranked.candidates.sortedByDescending { it.score(weights) }.map { it.span }
    }

    suspend fun record(comparison: QuoteComparison, choice: Int?): Boolean = runInterruptible(Dispatchers.IO) {
        store.record(comparison, choice).also { if (it && choice != null) changes.update { it + 1 } }
    }

    suspend fun undo(comparisonId: String): Boolean = runInterruptible(Dispatchers.IO) {
        store.undo(comparisonId).also { if (it) changes.update { it + 1 } }
    }
}
