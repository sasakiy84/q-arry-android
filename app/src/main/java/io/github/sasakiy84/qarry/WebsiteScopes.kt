package io.github.sasakiy84.qarry

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

internal data class FeedDiscovery(
    val feeds: List<FeedDocument> = emptyList(),
    val website: WebsiteScopes? = null,
)

internal data class WebsiteScopes(val html: String, val options: List<WebsiteScope>)

internal data class WebsiteScope(
    val label: String,
    val feed: FeedDocument,
    val highlightIds: List<Int>,
)

internal fun websiteScopes(
    document: Document,
    url: String,
    etag: String?,
    modified: String?,
): WebsiteScopes? {
    val selectors = suggestedItemSelectors(document, url)
    val primary = selectors.firstNotNullOfOrNull { extractHtmlFeed(document, url, it) } ?: return null
    val primaryKeys = primary.articles.map { it.key }.toSet()
    val primarySelector = requireNotNull(primary.htmlItemSelector)
    val rows = matchedHtmlArticles(document.select(primarySelector), url).map { it.first }
    val options = mutableListOf<WebsiteScope>()
    val keys = mutableSetOf<Set<String>>()
    val markers = mutableMapOf<Element, Int>()
    val observedAt = System.currentTimeMillis()
    document.select("[$SCOPE_MARKER]").removeAttr(SCOPE_MARKER)

    fun add(label: String, feed: FeedDocument?, firstOnly: Boolean = false) {
        if (feed == null || options.size >= MAX_OPTIONS) return
        val selected = matchedHtmlArticles(document.select(requireNotNull(feed.htmlItemSelector)), url)
        val displayed = if (firstOnly) selected.take(1) else selected
        val articles = displayed.map { it.second }
        if (articles.isEmpty() || !keys.add(articles.map { it.key }.toSet())) return
        val ids = displayed.map { (node, _) -> markers.getOrPut(node) { markers.size } }
        val displayLabel = if (options.any { it.label == label }) "$label（${options.size + 1}）" else label
        val watch = encodeWebsiteWatch(watchFor(selected, firstOnly))
        options.add(WebsiteScope(displayLabel, feed.copy(
            articles = articles, etag = etag, modified = modified, htmlWatch = watch, observedAt = observedAt,
        ), ids))
    }

    val groups = rows.groupBy { row ->
        if (row.normalName() == "tr") row.closest("table") else row.parent()
    }.filterKeys { it != null }.mapKeys { requireNotNull(it.key) }
    add(if (groups.size > 1) "一覧全体" else groups.keys.firstOrNull()?.sectionLabel() ?: "一覧全体", primary)
    if (groups.size > 1) {
        for ((index, entry) in groups.entries.withIndex()) {
            val (container, items) = entry
            val selector = if (container.normalName() == "table") {
                "${container.cssSelector()} > tbody > tr"
            } else {
                "${container.cssSelector()} > ${items.first().normalName()}"
            }
            val feed = extractHtmlFeed(document, url, selector)
            if (feed == null || feed.articles.size >= primaryKeys.size || feed.articles.any { it.key !in primaryKeys }) continue
            add(container.sectionLabel() ?: "範囲 ${index + 1}", feed)
        }
    }
    val hasSectionOptions = options.size > 1
    for (selector in selectors) {
        if (options.size >= MAX_OPTIONS) break
        val feed = extractHtmlFeed(document, url, selector) ?: continue
        if (hasSectionOptions && feed.articles.all { it.key in primaryKeys }) continue
        val firstItem = document.selectFirst(requireNotNull(feed.htmlItemSelector)) ?: continue
        add(firstItem.sectionLabel() ?: "候補 ${options.size + 1}", feed)
    }
    if (options.size == 1 && rows.size > 1) {
        add("先頭の1件", primary, firstOnly = true)
    }

    for ((element, id) in markers) element.attr(SCOPE_MARKER, id.toString())
    return WebsiteScopes(renderPreview(document, markers.keys), options)
}

private fun Element.sectionLabel(): String? {
    val caption = selectFirst("caption")?.text()?.takeIf(String::isNotBlank)
    if (caption != null) return caption.take(70)
    return precedingSectionHeading()?.text()?.take(70)?.takeIf(String::isNotBlank)
}

private fun renderPreview(document: Document, highlighted: Set<Element>): String {
    val main = document.selectFirst(MAIN_CONTENT)?.takeIf { root ->
        highlighted.all { it.parents().contains(root) }
    } ?: document.body()
    val safeContent = Jsoup.clean(
        main.html(),
        Safelist.relaxed()
            .addTags("main", "section", "article", "figure", "figcaption", "caption", "time")
            .addTags("header", "nav", "aside", "footer", "dl", "dt", "dd")
            .addAttributes(":all", SCOPE_MARKER)
            .removeAttributes("a", "href")
            .removeTags("img"),
    )
    return """
        <!doctype html><html lang="ja"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        body { margin: 12px; background: #fbfaf6; color: #1c2333; font: 15px/1.55 sans-serif; }
        h1,h2,h3,h4 { line-height: 1.35; margin: 16px 0 6px; }
        h1 { font-size: 21px; } h2,h3,h4 { font-size: 16px; }
        figure { margin: 8px 0 14px; }
        table { border-collapse: collapse; width: 100%; font-size: 13px; }
        th,td { border: 1px solid #d8dae2; padding: 6px; vertical-align: top; overflow-wrap: anywhere; }
        th { background: #eceef3; text-align: left; }
        [$SCOPE_MARKER] { scroll-margin-top: 12px; }
        .qarry-selected { background: #e3eaff; outline: 2px solid #2851c7; outline-offset: -2px; }
        tr.qarry-selected > td { background: #e3eaff; border-top-color: #2851c7; border-bottom-color: #2851c7; }
        </style></head><body>$safeContent</body></html>
    """.trimIndent()
}

internal const val SCOPE_MARKER = "data-qarry-item"
private const val MAX_OPTIONS = 12
