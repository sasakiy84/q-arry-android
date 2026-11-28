package io.github.sasakiy84.qarry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale

internal const val MAX_ARCHIVE_CHARS = 200_000
internal const val EMBEDDING_DIMENSIONS = 256

internal data class ArchiveCandidate(val id: Long, val url: String)
internal data class SearchDocument(val id: Long, val title: String, val storedBody: String)
internal data class SearchStats(
    val articles: Long,
    val archived: Long,
    val indexed: Long,
    val archiveBytes: Long,
    val lexicalBytes: Long?,
    val vectors: Long,
    val vectorBytes: Long?,
    val modelBytes: Long,
    val semanticEnabled: Boolean,
)

internal data class ArticleSearchResult(val listing: ArticleListing, val semanticUnavailable: Boolean = false)

internal fun searchWords(text: String): List<String> =
    Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}\\p{M}]+")).filter(String::isNotBlank)

internal fun japaneseIndexTokens(text: String): String {
    val letters = linkedSetOf<Int>()
    val tokens = StringBuilder()
    for (word in searchWords(text.take(MAX_ARCHIVE_CHARS))) {
        val points = word.codePoints().toArray()
        points.forEach(letters::add)
        points.asSequence().zipWithNext().forEach { (first, second) ->
            tokens.append("b").appendCodePoint(first).appendCodePoint(second).append(' ')
        }
        tokens.append("gap ")
    }
    for (letter in letters) tokens.append("u").appendCodePoint(letter).append(' ')
    return tokens.toString()
}

internal fun japaneseMatchQuery(words: List<String>): String = words.joinToString(" ") { word ->
    val points = word.codePoints().toArray()
    if (points.size == 1) "u$word" else points.asSequence().zipWithNext().joinToString(" ", "\"", "\"") { (first, second) ->
        StringBuilder().append("b").appendCodePoint(first).appendCodePoint(second).toString()
    }
}

internal fun encodeEmbedding(vector: FloatArray): ByteArray =
    ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        asFloatBuffer().put(vector)
    }.array()

internal fun embeddingSimilarity(vector: FloatArray, embedding: ByteArray): Float {
    val values = ByteBuffer.wrap(embedding).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    if (values.remaining() != vector.size) return -1f
    var sum = 0f
    for (value in vector) sum += value * values.get()
    return sum
}

internal fun hybridArticleOrder(lexical: List<Long>, semantic: List<Long>): List<Long> {
    val scores = linkedMapOf<Long, Double>()
    for (ranking in listOf(lexical, semantic)) {
        ranking.forEachIndexed { index, id -> scores[id] = (scores[id] ?: 0.0) + 1.0 / (61 + index) }
    }
    return scores.keys.sortedByDescending(scores::getValue)
}
