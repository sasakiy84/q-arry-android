package io.github.sasakiy84.qarry

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

private const val MAX_ARTICLES = 500
private const val MAX_PARENTS_TO_INSPECT = 4_000
private const val MAX_CARDS_TO_SCORE = 40
internal const val MAIN_CONTENT = "main, [role=main]"
internal const val HEADING = "h1, h2, h3, h4, h5, h6"
private const val NEWS_TITLE = "[itemprop=headline], .title, .headline, .article-title, .entry-title, .post-title"
private const val DESCRIPTION = "[itemprop=description], .description, .summary, .excerpt, .teaser, .lead"
private const val BYLINE = ".byline, .author, .date, .timestamp, [itemprop=author], [itemprop=datePublished]"
private const val IGNORE = "nav, aside, footer, [role=navigation], [hidden], [aria-hidden=true], " +
    ".breadcrumb, .breadcrumbs, .pagination, .related, .related-posts, .recommendations, .recommended, .share"
private val selectorToken = Regex("[a-zA-Z][a-zA-Z0-9_-]*")
private val articleHint = Regex("(^|[ _-])(articles?|posts?|news|updates|entries|entry|stories|episodes?)([ _-]|$)", RegexOption.IGNORE_CASE)
private val genericLinkText = setOf("続きを読む", "もっと読む", "詳しくはこちら", "詳細", "read more", "learn more", "more")
private val skipLinkText = setOf("前へ", "次へ", "次のページ", "previous", "next")
private val descriptionColumnNames = setOf("内容", "概要", "説明", "description", "summary", "abstract")
private val updatedColumnNames = setOf("更新日時", "更新日", "最終更新日時", "最終更新日", "updated", "last updated", "modified")
private val publishedColumnNames = setOf("公開日時", "公開日", "公開年月", "掲載日時", "掲載日", "published", "date published")
private const val UPDATED_DATE = "[itemprop=dateModified], [data-updated-at], time.updated, time.modified"
private const val PUBLISHED_DATE = "[itemprop=datePublished], [data-published-at], time.published"

internal fun extractHtmlFeed(document: Document, pageUrl: String, savedSelector: String? = null): FeedDocument? {
    val selector = savedSelector ?: inferItemSelector(document, pageUrl) ?: return null
    val cards = document.select(selector)
    val rowSelector = when {
        cards.isEmpty() -> null
        cards.all { it.normalName() == "table" } -> "$selector > tbody > tr"
        cards.all { it.normalName() != "table" && it.select("table").size == 1 } -> "$selector table > tbody > tr"
        else -> null
    }
    val tableArticles = rowSelector?.let { document.select(it).feedArticles(pageUrl) }
        ?.takeIf { it.size > cards.size }
    val articles = tableArticles ?: cards.feedArticles(pageUrl)
    if (articles.isEmpty()) return null
    return websiteDocument(document, pageUrl, if (tableArticles != null) requireNotNull(rowSelector) else selector, articles)
}

internal fun websiteDocument(document: Document, pageUrl: String, selector: String, articles: List<FeedArticle>): FeedDocument =
    FeedDocument(
        url = pageUrl,
        title = document.title().take(500).ifBlank { URI(pageUrl).host },
        home = pageUrl,
        articles = articles,
        htmlItemSelector = selector,
    )

private fun Iterable<Element>.feedArticles(pageUrl: String): List<FeedArticle> =
    matchedHtmlArticles(this, pageUrl).map { it.second }

internal fun matchedHtmlArticles(cards: Iterable<Element>, pageUrl: String): List<Pair<Element, FeedArticle>> = cards.asSequence()
    .filter { it.closest(IGNORE) == null }
    .mapNotNull { card ->
        if (card.normalName() == "tr" && card.children().none { it.normalName() == "td" }) return@mapNotNull null
        val article = extractCard(card, pageUrl) ?: return@mapNotNull null
        val tableDescription = card.tableCell(descriptionColumnNames)?.metadataText()
        card to if (tableDescription == null) article else article.copy(body = tableDescription.take(2_000))
    }
    .distinctBy { it.second.key }
    .take(MAX_ARTICLES)
    .toList()

private fun Element.tableCell(headers: Set<String>): Element? {
    if (normalName() != "tr") return null
    val table = closest("table") ?: return null
    val header = table.children().firstOrNull { it.normalName() == "thead" }
        ?.children()?.firstOrNull { it.normalName() == "tr" } ?: return null
    val column = header.children().indexOfFirst {
        it.normalName() == "th" && it.text().trim().lowercase() in headers
    }
    return children().getOrNull(column)?.takeIf { it.normalName() == "td" }
}

private data class ListCandidate(
    val parent: Element,
    val itemSelectors: List<String>,
    val score: Int,
    val urls: Set<String>,
) {
    val selector: String get() = "${parent.cssSelector()} > ${itemSelectors.first()}"
}

private fun inferItemSelector(document: Document, pageUrl: String): String? {
    val candidates = rankedLists(document, pageUrl)
    val best = candidates.firstOrNull() ?: return null
    val ambiguous = candidates.any { it.score == best.score && it.urls.none(best.urls::contains) }
    return if (ambiguous) null else preferredSelector(document, best)
}

internal fun suggestedItemSelectors(document: Document, pageUrl: String): List<String> =
    rankedLists(document, pageUrl).distinctBy { it.urls }.take(16).map { preferredSelector(document, it) }

private fun rankedLists(document: Document, pageUrl: String): List<ListCandidate> {
    val candidates = mutableListOf<ListCandidate>()
    val cachedCards = mutableMapOf<Element, FeedArticle?>()
    val parents = document.body().getAllElements().asSequence()
        .filter { it.childrenSize() > 1 && it.closest(IGNORE) == null && it.closest("header") == null }
        .take(MAX_PARENTS_TO_INSPECT)
    for (parent in parents) {
        val siblings = parent.children().filter { it.closest(IGNORE) == null }
        for ((tag, sameTag) in siblings.groupBy { it.normalName() }) {
            if (sameTag.size < 2 || !selectorToken.matches(tag)) continue
            val classGroups = mutableMapOf<String, MutableList<Element>>()
            for (card in sameTag) {
                for (token in card.classNames().filter(selectorToken::matches)) {
                    classGroups.getOrPut("$tag.$token") { mutableListOf() }.add(card)
                }
            }
            val groups = mapOf(tag to sameTag) + classGroups.filterValues { it.size in 2 until sameTag.size }
            for ((childSelector, cards) in groups) {
                val articles = cards.take(MAX_CARDS_TO_SCORE).mapNotNull { card ->
                    if (!cachedCards.containsKey(card)) cachedCards[card] = extractCard(card, pageUrl)
                    cachedCards[card]
                }.distinctBy { it.key }
                if (articles.size < 2) continue
                val semantic = tag == "article" || articleHint.containsMatchIn(
                    "${parent.id()} ${parent.className()} ${cards.first().className()}",
                )
                val route = articles.mapNotNull { it.url?.let(::articleRoute) }.groupingBy { it }.eachCount()
                val repeatsRoute = route.values.any { it >= 2 }
                if (!semantic && !repeatsRoute) continue
                val score = articles.size.coerceAtMost(8) +
                    (if (parent.closest(MAIN_CONTENT) != null) 12 else 0) +
                    (if (semantic) 8 else 0) + (if (tag == "article") 4 else 0) +
                    (if (repeatsRoute) 2 else 0) +
                    articles.count { it.body != null || it.thumbnailUrl != null }.coerceAtMost(3)
                val representative = cards.first()
                val commonClasses = representative.classNames().filter { token ->
                    selectorToken.matches(token) && cards.all { it.hasClass(token) }
                }
                candidates += ListCandidate(
                    parent = parent,
                    itemSelectors = (listOf(childSelector, tag) + commonClasses.take(8).map { "$tag.$it" }).distinct(),
                    score = score,
                    urls = articles.map { it.key }.toSet(),
                )
            }
        }
    }
    return candidates.sortedByDescending { it.score }
}

private val generatedClass = Regex("""(?:^|[-_])[a-z]*[0-9][a-z0-9]*$""", RegexOption.IGNORE_CASE)
private val positionalSelector = Regex(":nth-child\\(")

private fun preferredSelector(document: Document, candidate: ListCandidate): String {
    val original = candidate.selector
    val originalNodes = document.select(original).filter { it.closest(IGNORE) == null }.toSet()
    val anchors = (sequenceOf(candidate.parent) + candidate.parent.parents().asSequence()).take(6).flatMap { node ->
        val tag = node.normalName()
        val choices = buildList {
            if (selectorToken.matches(node.id())) add("#${node.id()}")
            if (tag == "main") add(tag)
            node.classNames().filter { selectorToken.matches(it) && !generatedClass.containsMatchIn(it) }
                .take(3).forEach { add("$tag.$it") }
        }
        choices.asSequence().filter { document.select(it).singleOrNull() === node }
    }.distinct().take(6).toList()
    val paths = listOf(original) + anchors.flatMap { anchor ->
        candidate.itemSelectors.flatMap { item -> listOf("$anchor $item", "$anchor > $item") }
    }
    return paths.distinct().sortedWith(compareByDescending<String> { path ->
        val tokens = path.split(' ', '.', '#', '>')
        (if (path.startsWith('#')) 4 else 0) +
            (if (tokens.any(articleHint::containsMatchIn)) 3 else 0) +
            (if (articleHint.containsMatchIn(path.substringAfterLast(' ').substringAfter('.', ""))) 3 else 0) -
            12 * positionalSelector.findAll(path).count() -
            4 * tokens.count(generatedClass::containsMatchIn) - 2 * path.count { it == '>' }
    }.thenBy(String::length)).firstOrNull { path ->
        document.select(path).filter { it.closest(IGNORE) == null }.toSet() == originalNodes
    } ?: original
}

internal fun articleRoute(url: String): String? {
    val address = URI(url)
    val folder = address.rawPath.trimEnd('/').substringBeforeLast('/')
    return when {
        address.rawQuery != null -> "${address.rawAuthority}${address.rawPath}?${address.rawQuery.substringBefore('=')}"
        folder.isNotEmpty() -> "${address.rawAuthority}$folder/"
        else -> null
    }
}

private data class CardLink(val element: Element, val url: String, val inHeading: Boolean)

private fun extractCard(card: Element, pageUrl: String): FeedArticle? {
    val links = card.select("a[href]").mapNotNull { anchor ->
        if (anchor.closest(IGNORE) != null || anchor.attr("rel") in setOf("prev", "next", "author", "tag")) {
            return@mapNotNull null
        }
        val href = anchor.attr("href").trim()
        if (href.isEmpty() || href.startsWith('#') || anchor.text().lowercase() in skipLinkText) return@mapNotNull null
        val url = webUrl(anchor.absUrl("href"))?.takeUnless { it == pageUrl } ?: return@mapNotNull null
        val heading = anchor.closest(HEADING)
        val insideHeading = heading === card || heading?.parents()?.contains(card) == true
        CardLink(anchor, url, insideHeading || anchor.selectFirst(HEADING) != null)
    }
    val headingUrls = links.filter { it.inHeading }.map { it.url }.distinct()
    if (headingUrls.size > 1) return null
    val primaryLink = links.firstOrNull { it.inHeading || it.element.attr("itemprop") == "url" }
        ?: links.firstOrNull() ?: return null
    val anchor = primaryLink.element
    val description = card.select(DESCRIPTION).firstNotNullOfOrNull { it.metadataText() }
        ?: card.select("p").firstNotNullOfOrNull { paragraph ->
            if (paragraph.closest(BYLINE) == null && paragraph.selectFirst("time") == null) {
                paragraph.metadataText()
            } else {
                null
            }
        }
    val title = card.selectFirst(NEWS_TITLE)?.metadataText()
        ?: card.selectFirst(HEADING)?.metadataText()
        ?: anchor.attr("aria-label").usableTitle()
        ?: anchor.attr("title").usableTitle()
        ?: anchor.text().takeIf { description == null || !it.contains(description) }?.usableTitle()
    val thumbnail = card.select("img").asSequence()
        .filter { it.closest(IGNORE) == null && !it.hasClass("avatar") && !it.hasClass("logo") }
        .firstNotNullOfOrNull(::imageUrl)
    val updatedAt = card.articleTimestamp(UPDATED_DATE, "data-updated-at", updatedColumnNames)
    val publishedTime = card.articleTimestamp(PUBLISHED_DATE, "data-published-at", publishedColumnNames)
    return FeedArticle(
        key = primaryLink.url,
        title = title?.take(500),
        url = primaryLink.url,
        body = description?.takeUnless { it == title }?.take(2_000),
        published = publishedTime ?: updatedAt,
        thumbnailUrl = thumbnail,
        updatedAt = updatedAt,
        publishedTime = publishedTime,
    )
}

private fun Element.articleTimestamp(selector: String, dataAttribute: String, headers: Set<String>): Long? {
    val field = select(selector).firstNotNullOfOrNull { element ->
        if (element.closest(IGNORE) == null) element.timestamp(dataAttribute) else null
    }
    if (field != null) return field
    val cell = tableCell(headers) ?: return null
    return cell.selectFirst("time")?.timestamp(dataAttribute) ?: cell.timestamp(dataAttribute)
}

private fun Element.timestamp(dataAttribute: String): Long? =
    sequenceOf(attr(dataAttribute), attr("datetime"), attr("content"), text()).firstNotNullOfOrNull(::sourceTimestamp)

private fun imageUrl(image: Element): String? {
    sequenceOf("data-src", "data-lazy-src", "data-original")
        .firstNotNullOfOrNull { webUrl(image.absUrl(it)) }?.let { return it }
    val pictureSource = image.parent()?.takeIf { it.normalName() == "picture" }?.selectFirst("source[srcset]")
    val sources = listOfNotNull(pictureSource?.attr("srcset"), image.attr("data-srcset"), image.attr("srcset"))
    return sources.asSequence()
        .filterNot { it.startsWith("data:") }
        .flatMap { it.split(',').asSequence() }
        .map { it.trim().takeWhile { character -> !character.isWhitespace() } }
        .firstNotNullOfOrNull { resolveUrl(image.baseUri(), it) }
        ?: webUrl(image.absUrl("src"))
}

private fun Element.metadataText(): String? =
    (attr("content").ifBlank { text() }).trim().takeIf(String::isNotEmpty)

private fun String.usableTitle(): String? = trim()
    .takeIf { it.isNotEmpty() && it.lowercase() !in genericLinkText }
