package io.github.sasakiy84.qarry

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

internal fun extractArticleText(document: Document): String? {
    if (document.select("meta[name=robots], meta[name=googlebot]").any {
            it.attr("content").split(Regex("[,\\s]+")).any { directive -> directive.equals("noarchive", true) }
        }) return null
    val body = document.body().clone()
    body.select(
        "script, style, noscript, template, nav, aside, footer, form, button, iframe, [hidden], [aria-hidden=true], " +
            "[role=navigation], [role=complementary], [role=banner], [role=contentinfo], " +
            "[class~=(?i)(?:^|[-_\\s])(?:comments?|share|social|related|advertisement|cookie|breadcrumb|sidebar)(?:$|[-_\\s])]",
    ).remove()
    val candidates = buildSet {
        addAll(body.select("article, main, [role=main], [itemprop=articleBody], .entry-content, .post-content, .article-body"))
        addAll(body.select("p").mapNotNull { it.parent() })
        add(body)
    }
    return candidates.mapNotNull { candidate ->
        val text = articleParagraphs(candidate)
        if (text.length < 80) return@mapNotNull null
        val linkLength = candidate.select("a").sumOf { it.text().length }
        val linkFraction = linkLength.toDouble() / text.length
        if (linkFraction > 0.45 || candidate.select("article").size > 1) return@mapNotNull null
        val paragraphs = candidate.select("p").count { it.text().length >= 35 }.coerceAtMost(20)
        val explicit = candidate.normalName() == "article" || candidate.hasAttr("itemprop") ||
            candidate.hasClass("entry-content") || candidate.hasClass("post-content") || candidate.hasClass("article-body")
        if (!explicit && paragraphs == 0 && text.length < 360) return@mapNotNull null
        val score = text.length.coerceAtMost(12_000) * (1 - linkFraction) +
            paragraphs * 100 + if (explicit) 600 else 0
        text to score
    }.maxByOrNull { it.second }?.first?.take(MAX_ARCHIVE_CHARS)
}

private fun articleParagraphs(candidate: Element): String {
    if (candidate.normalName() in articleBlockTags) return candidate.wholeText().trim()
    val blocks = candidate.select(articleBlockTags.joinToString(",")).filter { block ->
        block.parents().takeWhile { it !== candidate }.none { it.normalName() in articleBlockTags }
    }
    return if (blocks.isEmpty()) candidate.wholeText().trim()
    else blocks.map { if (it.normalName() == "pre") it.wholeText().trim() else it.text() }
        .filter(String::isNotBlank).joinToString("\n\n")
}

private val articleBlockTags = setOf("h1", "h2", "h3", "h4", "p", "pre", "li", "blockquote", "dt", "dd", "figcaption")

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
