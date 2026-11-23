package io.github.sasakiy84.qarry

import java.security.MessageDigest
import java.text.BreakIterator
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

internal const val QUOTE_FEATURE_VERSION = 1
internal const val QUOTE_FEATURE_SIZE = QUOTE_EMBEDDING_SIZE + 6
internal const val QUOTE_EXPLORATION_RATE = 0.2
internal const val QUOTE_LEARNING_RATE = 0.2f
private const val REGULARIZATION = 0.025f
private const val MAX_QUOTE_CANDIDATES = 40

internal data class QuoteSource(val articleId: Long, val title: String, val body: String)

// Offsets are UTF-16 code units into the archived body, with an exclusive end.
internal data class QuoteSpan(val start: Int, val end: Int, val text: String)

internal data class QuoteCandidate(val span: QuoteSpan, val features: FloatArray, val baseScore: Float) {
    fun score(weights: FloatArray): Float = baseScore + quoteDot(features, weights)
}

internal data class RankedQuotes(
    val articleId: Long,
    val title: String,
    val bodyVersion: String,
    val candidates: List<QuoteCandidate>,
) {
    fun compare(fitting: List<QuoteCandidate>, weights: FloatArray, random: Random = Random.Default): QuoteComparison? {
        if (fitting.size < 2) return null
        val ranked = fitting.sortedByDescending { it.score(weights) }
        val first: QuoteCandidate
        val second: QuoteCandidate
        if (random.nextDouble() < QUOTE_EXPLORATION_RATE) {
            first = ranked.random(random)
            second = ranked.filter { it !== first }.random(random)
        } else {
            first = ranked.first()
            second = ranked.drop(1).take(8).maxBy { candidate ->
                val similarity = (0 until QUOTE_EMBEDDING_SIZE).sumOf {
                    (candidate.features[it] * first.features[it]).toDouble()
                }.toFloat()
                -abs(first.score(weights) - candidate.score(weights)) + 0.3f * (1f - similarity)
            }
        }
        val displayed = if (random.nextBoolean()) listOf(first, second) else listOf(second, first)
        return QuoteComparison(UUID.randomUUID().toString(), articleId, title, bodyVersion, displayed)
    }
}

internal data class QuoteComparison(
    val id: String,
    val articleId: Long,
    val title: String,
    val bodyVersion: String,
    val displayed: List<QuoteCandidate>,
)

internal fun quoteSpans(body: String): List<QuoteSpan> {
    val result = ArrayList<QuoteSpan>()
    val seen = HashSet<String>()
    val sentences = BreakIterator.getSentenceInstance(Locale.JAPANESE)
    for (paragraph in quoteParagraph.findAll(body)) {
        val text = paragraph.value
        sentences.setText(text)
        var start = sentences.first()
        for (end in generateSequence { sentences.next().takeIf { it != BreakIterator.DONE } }) {
            val sentence = text.substring(start, end)
            val trimmed = sentence.trim()
            val offset = paragraph.range.first + start + sentence.indexOfFirst { !it.isWhitespace() }
            start = end
            if (trimmed.length !in 14..200 || trimmed.count(Char::isLetter) < 10 ||
                !sentenceEnding.containsMatchIn(trimmed) || startsMidThought.containsMatchIn(trimmed) ||
                trimmed.contains("://") || !seen.add(trimmed.replace(quoteWhitespace, " ").lowercase(Locale.ROOT))
            ) continue
            result.add(QuoteSpan(offset, offset + trimmed.length, trimmed))
        }
    }
    if (result.size <= MAX_QUOTE_CANDIDATES) return result
    return List(MAX_QUOTE_CANDIDATES) { result[it * result.lastIndex / (MAX_QUOTE_CANDIDATES - 1)] }
}

internal fun rankQuotes(source: QuoteSource, encoder: QuoteEncoder, minimum: Int): RankedQuotes {
    val spans = quoteSpans(source.body)
    if (spans.size < minimum) return RankedQuotes(source.articleId, source.title, "", emptyList())
    val vectors = encoder.encode(listOf(source.title) + spans.map { it.text })
    val titleVector = vectors.first()
    val bodyVector = normalizeQuoteVector(FloatArray(QUOTE_EMBEDDING_SIZE) { dimension ->
        (1 until vectors.size).sumOf { vectors[it][dimension].toDouble() }.toFloat()
    })
    val candidates = spans.mapIndexed { index, span ->
        val encoded = vectors[index + 1]
        val titleMatch = quoteDot(encoded, titleVector).coerceIn(-1f, 1f)
        val bodyMatch = quoteDot(encoded, bodyVector).coerceIn(-1f, 1f)
        val length = (1f - abs(span.text.length - 70) / 130f).coerceIn(0f, 1f)
        val lead = 1f - span.start.toFloat() / source.body.length
        val numeral = if (span.text.any(Char::isDigit)) 1f else 0f
        val question = if ('?' in span.text || '？' in span.text) 1f else 0f
        val features = encoded + floatArrayOf(titleMatch, bodyMatch, length, lead, numeral, question)
        val baseScore = 0.68f * titleMatch + 0.22f * bodyMatch + 0.2f * length + 0.12f * lead
        QuoteCandidate(span, features, baseScore)
    }
    return RankedQuotes(source.articleId, source.title, quoteBodyVersion(source.body), candidates)
}

internal fun updateQuoteWeights(weights: FloatArray, preferred: QuoteCandidate, other: QuoteCandidate): FloatArray {
    val difference = preferred.score(weights) - other.score(weights)
    val gradient = (1.0 / (1.0 + exp(difference.toDouble().coerceIn(-30.0, 30.0)))).toFloat()
    return FloatArray(weights.size) { index ->
        weights[index] + QUOTE_LEARNING_RATE * (
            gradient * (preferred.features[index] - other.features[index]) - REGULARIZATION * weights[index]
        )
    }
}

internal fun normalizeQuoteVector(vector: FloatArray): FloatArray {
    val norm = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
    if (norm > 0f && norm.isFinite()) {
        for (index in vector.indices) vector[index] /= norm
    } else {
        vector.fill(0f)
    }
    return vector
}

internal fun quoteBodyVersion(body: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)))

private fun quoteDot(a: FloatArray, b: FloatArray): Float {
    var result = 0f
    for (index in a.indices) result += a[index] * b[index]
    return result
}

private val quoteParagraph = Regex("[^\\r\\n]+")
private val quoteWhitespace = Regex("\\s+")
private val sentenceEnding = Regex("""[。！？.!?][」』）】”’"')\]]*$""")
private val startsMidThought = Regex(
    """^(?:しかし|したがって|そのため|それでも|このこと|このとき|こうして|一方、|そして|また、|""" +
        """(?i:however|therefore|thus|nevertheless|moreover)\b)""",
)
