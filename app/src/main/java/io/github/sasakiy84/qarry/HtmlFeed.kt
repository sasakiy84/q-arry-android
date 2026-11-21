package io.github.sasakiy84.qarry

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

private const val MAX_ARTICLES = 500
private const val MAX_PARENTS_TO_INSPECT = 4_000
private const val MAX_CARDS_TO_SCORE = 40
private const val MAIN_CONTENT = "main, [role=main]"
private const val HEADING = "h1, h2, h3, h4, h5, h6"
private const val NEWS_TITLE = "[itemprop=headline], .title, .headline, .article-title, .entry-title, .post-title"
private const val DESCRIPTION = "[itemprop=description], .description, .summary, .excerpt, .teaser, .lead"
private const val BYLINE = ".byline, .author, .date, .timestamp, [itemprop=author], [itemprop=datePublished]"
private const val IGNORE = "nav, aside, footer, [role=navigation], [hidden], [aria-hidden=true], " +
    ".breadcrumb, .breadcrumbs, .pagination, .related, .related-posts, .recommendations, .recommended, .share"
private val selectorToken = Regex("[a-zA-Z][a-zA-Z0-9_-]*")
private val articleHint = Regex("(^|[ _-])(articles?|posts?|news|updates|entries|entry|stories|episodes?)([ _-]|$)", RegexOption.IGNORE_CASE)
private val genericLinkText = setOf("続きを読む", "もっと読む", "詳しくはこちら", "詳細", "read more", "learn more", "more")
private val skipLinkText = setOf("前へ", "次へ", "次のページ", "previous", "next")

internal fun extractHtmlFeed(document: Document, pageUrl: String, savedSelector: String? = null): FeedDocument? {
    val selector = savedSelector ?: inferItemSelector(document, pageUrl) ?: return null
    val cards = document.select(selector)
    val articles = cards.asSequence()
        .filter { it.closest(IGNORE) == null }
        .mapNotNull { extractCard(it, pageUrl) }
        .distinctBy { it.key }
        .take(MAX_ARTICLES)
        .toList()
    if (articles.isEmpty()) return null
    return FeedDocument(
        url = pageUrl,
        title = document.title().take(500).ifBlank { URI(pageUrl).host },
        home = pageUrl,
        articles = articles,
        htmlItemSelector = selector,
    )
}

private data class ListCandidate(val selector: String, val score: Int, val urls: Set<String>)

private fun inferItemSelector(document: Document, pageUrl: String): String? {
    var best: ListCandidate? = null
    var ambiguous = false
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
                val commonClass = representative.classNames().firstOrNull { token ->
                    selectorToken.matches(token) && cards.all { it.hasClass(token) }
                }
                val itemSelector = if (childSelector == tag && commonClass != null) "$tag.$commonClass" else childSelector
                val candidate = ListCandidate(
                    selector = "${parent.cssSelector()} > $itemSelector",
                    score = score,
                    urls = articles.map { it.key }.toSet(),
                )
                when {
                    best == null || score > best.score -> {
                        best = candidate
                        ambiguous = false
                    }
                    score == best.score && best.urls.intersect(candidate.urls).isEmpty() -> ambiguous = true
                }
            }
        }
    }
    return best?.selector?.takeUnless { ambiguous }
}

private fun articleRoute(url: String): String? {
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
    return FeedArticle(
        key = primaryLink.url,
        title = title?.take(500),
        url = primaryLink.url,
        body = description?.takeUnless { it == title }?.take(2_000),
        published = null,
        thumbnailUrl = thumbnail,
    )
}

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
