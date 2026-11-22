package io.github.sasakiy84.qarry

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

internal const val WEBSITE_SCOPE_CHANGED = "登録した範囲を確認できなかったため、更新を保留しました。範囲を選び直してください。"

internal data class WebsiteHeading(val id: String, val tag: String, val text: String)

internal data class WebsiteTable(val id: String, val caption: String, val columns: List<String>)

internal data class WebsiteWatch(
    val heading: WebsiteHeading?,
    val table: WebsiteTable?,
    val firstOnly: Boolean,
    val routes: Set<String>,
    val keys: Set<String>,
) {
    fun withArticles(articles: List<FeedArticle>): WebsiteWatch =
        if (articles.isEmpty()) this else copy(keys = articleKeys(articles))
}

internal fun watchFor(
    matches: List<Pair<Element, FeedArticle>>,
    firstOnly: Boolean = false,
): WebsiteWatch {
    val items = matches.map { it.first }
    val table = items.map { it.takeIf { item -> item.normalName() == "tr" }?.closest("table") }.distinct().singleOrNull()
    val heading = if (table != null) table.precedingSectionHeading() else {
        items.map { it.parent()?.precedingSectionHeading() }.distinct().singleOrNull()
    }
    val anchor = heading?.let { element ->
        val page = element.ownerDocument()
        element.headingIdentity().takeIf { page != null && it.text.isNotBlank() && it.findIn(page) === element }
    }
    val articles = matches.map { it.second }
    return WebsiteWatch(anchor, table?.tableIdentity(), firstOnly, articles.map(::watchRoute).toSet(), articleKeys(articles))
}

internal fun refreshWebsiteDocument(
    page: Document,
    url: String,
    selector: String,
    encodedWatch: String?,
    previousKeys: Set<String> = emptySet(),
): FeedDocument? {
    val watch = encodedWatch?.let { decodeWebsiteWatch(it) ?: return null }
    if (watch == null) {
        val positional = ":nth-child(" in selector
        val feed = extractHtmlFeed(page, url, selector)
        if (feed == null) {
            if (!positional || !selector.endsWith(" > tbody > tr") || previousKeys.isEmpty()) return null
            return recoverUnchangedLegacyTable(page, url, previousKeys)
        }
        if (positional && feed.articles.any { it.key !in previousKeys }) return null
        val matches = matchedHtmlArticles(page.select(requireNotNull(feed.htmlItemSelector)), url)
        return feed.copy(htmlWatch = encodeWebsiteWatch(watchFor(matches)))
    }

    val heading = watch.heading?.let { it.findIn(page) ?: return null }
    val matched = if (watch.table != null) {
        val shape = watch.table
        val selected = page.select(selector).mapNotNull { it.closest("table") }.distinct().singleOrNull()
        val tables = if (heading != null || shape.id.isNotEmpty()) page.select("table").filter { table ->
            (heading == null || table.precedingSectionHeading() === heading) && table.tableIdentity() == shape
        } else listOfNotNull(selected?.takeIf { it.tableIdentity() == shape })
        val table = tables.singleOrNull() ?: selected?.takeIf { tables.size > 1 && it in tables } ?: return null
        val rows = table.dataRows()
        val articles = matchedHtmlArticles(rows, url)
        if (articles.isEmpty() && (rows.isNotEmpty() || tables.size != 1)) return null
        // A different table can share the heading, column names, and link routes.
        if (articles.isNotEmpty() && (shape.id.isEmpty() || tables.size > 1)) {
            val sharedKeys = articleKeys(articles.map { it.second }).count(watch.keys::contains)
            val needed = minOf(2, articles.size, watch.keys.size.coerceAtLeast(1))
            if (sharedKeys < needed) return null
        }
        articles
    } else {
        val items = page.select(selector)
        val articles = matchedHtmlArticles(items, url)
        if (articles.isEmpty() || (heading != null && articles.any { it.first.parent()?.precedingSectionHeading() !== heading })) return null
        articles
    }
    val articles = matched.map { it.second }
    if (articles.any { watchRoute(it) !in watch.routes }) return null
    return websiteDocument(page, url, selector, if (watch.firstOnly) articles.take(1) else articles)
        .copy(htmlWatch = encodeWebsiteWatch(watch.withArticles(articles)))
}

private fun recoverUnchangedLegacyTable(page: Document, url: String, previousKeys: Set<String>): FeedDocument? {
    val recovered = page.select("table").asSequence().mapNotNull { table ->
        val items = matchedHtmlArticles(table.dataRows(), url)
        if (items.map { it.second.key }.toSet() == previousKeys) table to items else null
    }.take(2).toList().singleOrNull() ?: return null
    val (table, items) = recovered
    val selector = "${table.cssSelector()} > tbody > tr"
    return websiteDocument(page, url, selector, items.map { it.second }).copy(htmlWatch = encodeWebsiteWatch(watchFor(items)))
}

private fun Element.dataRows(): List<Element> = children().filter { it.normalName() == "tbody" }.flatMap { it.children() }
    .filter { row -> row.normalName() == "tr" && row.children().any { it.normalName() == "td" } }

internal fun Element.precedingSectionHeading(): Element? {
    var node: Element? = this
    while (node != null && !node.`is`(MAIN_CONTENT) && node.normalName() != "body") {
        var preceding = node.previousElementSibling()
        while (preceding != null) {
            if (preceding.`is`(HEADING)) return preceding
            preceding = preceding.previousElementSibling()
        }
        node = node.parent()
    }
    return null
}

private fun Element.headingIdentity(): WebsiteHeading = WebsiteHeading(id().take(180), normalName(), text().take(180))

private fun WebsiteHeading.findIn(page: Document): Element? {
    val headings = page.select(HEADING)
    if (id.isNotEmpty()) {
        val sameId = headings.filter { it.id() == id }
        if (sameId.isNotEmpty()) return sameId.singleOrNull()?.takeIf { it.normalName() == tag }
    }
    return headings.singleOrNull { it.normalName() == tag && it.text().take(180) == text }
}

private fun Element.tableIdentity(): WebsiteTable {
    val caption = children().firstOrNull { it.normalName() == "caption" }?.text().orEmpty().take(180)
    val header = children().firstOrNull { it.normalName() == "thead" }
        ?: children().firstOrNull { it.normalName() == "tbody" }?.children()?.firstOrNull { row ->
            row.children().isNotEmpty() && row.children().all { it.normalName() == "th" }
        }
    val columns = header?.select("th")?.eachText().orEmpty().take(24).mapIndexed { index, name -> "$index:${name.take(100)}" }
    return WebsiteTable(id().take(180), caption, columns)
}

private fun watchRoute(article: FeedArticle): String {
    val url = article.key
    return (articleRoute(url) ?: "${URI(url).rawAuthority}/").take(256)
}

private fun articleKeys(articles: List<FeedArticle>): Set<String> {
    val digest = MessageDigest.getInstance("SHA-256")
    return articles.mapTo(linkedSetOf()) {
        Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(it.key.toByteArray()).copyOf(12))
    }
}

internal fun encodeWebsiteWatch(watch: WebsiteWatch): String {
    val fields = listOf(
        watch.heading?.tag.orEmpty(), watch.heading?.id.orEmpty(), watch.heading?.text.orEmpty(),
        if (watch.table != null) "table" else "list", watch.table?.id.orEmpty(), watch.table?.caption.orEmpty(),
        watch.table?.columns.orEmpty().joinToString("\n"), watch.firstOnly.toString(),
        watch.routes.joinToString("\n"), watch.keys.joinToString("\n"),
    )
    val encoder = Base64.getUrlEncoder().withoutPadding()
    return fields.joinToString(".", prefix = "1.") { encoder.encodeToString(it.toByteArray()) }
}

internal fun decodeWebsiteWatch(value: String): WebsiteWatch? {
    if (!value.startsWith("1.") || value.length > 200_000) return null
    val decoder = Base64.getUrlDecoder()
    val fields = try {
        value.substring(2).split('.').map { String(decoder.decode(it), Charsets.UTF_8) }
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (fields.size != 10 || fields[3] !in setOf("table", "list") || fields[7] !in setOf("true", "false")) return null
    return WebsiteWatch(
        heading = fields[0].takeIf { it in setOf("h1", "h2", "h3", "h4", "h5", "h6") }
            ?.let { WebsiteHeading(fields[1], it, fields[2]) },
        table = if (fields[3] == "table") WebsiteTable(fields[4], fields[5], fields[6].lines().filter(String::isNotEmpty)) else null,
        firstOnly = fields[7] == "true",
        routes = fields[8].lines().filter(String::isNotEmpty).toSet(),
        keys = fields[9].lines().filter(String::isNotEmpty).toSet(),
    )
}
