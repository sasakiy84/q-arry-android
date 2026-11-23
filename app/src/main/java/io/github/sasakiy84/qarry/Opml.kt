package io.github.sasakiy84.qarry

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.XmlDeclaration
import org.jsoup.parser.Parser
import org.jsoup.select.QueryParser
import org.jsoup.select.Selector.SelectorParseException
import java.io.IOException
import java.io.InputStream
import java.net.URI

internal data class OpmlFeed(
    val url: String,
    val title: String,
    val homeUrl: String? = null,
    val htmlItemSelector: String? = null,
    val htmlWatch: String? = null,
    val isFavorite: Boolean = false,
    val keywordFilter: KeywordFilter? = null,
    val queueFilters: Map<String, KeywordFilter?> = emptyMap(),
    val notifications: FeedNotificationMode = FeedNotificationMode.Default,
)

internal data class OpmlContents(
    val feeds: List<OpmlFeed>,
    val duplicateCount: Int,
    val unsupportedCount: Int,
    val queues: List<String> = emptyList(),
)

internal class OpmlFormatException(message: String) : IOException(message)

internal fun readOpml(input: InputStream): OpmlContents {
    val bytes = input.readNBytes(MAX_OPML_BYTES + 1)
    if (bytes.size > MAX_OPML_BYTES) throw OpmlFormatException("OPMLファイルが大きすぎます。")
    val document = Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
    val opml = document.children().singleOrNull()?.takeIf { it.normalName() == "opml" }
    val body = opml?.children()?.firstOrNull { it.normalName() == "body" }
        ?: throw OpmlFormatException("OPMLファイルを選んでください。")
    val extension = opml.attributes().firstOrNull {
        it.key.startsWith("xmlns:") && it.value == QARRY_OPML_NAMESPACE
    }?.key?.substringAfter(':')?.plus(":")
    val queues = extension?.let { prefix ->
        opml.children().firstOrNull { it.normalName() == "head" }?.children()
            ?.filter { it.tagName() == "${prefix}queue" }?.mapNotNull(::opmlQueueName)
    }.orEmpty()

    val feeds = linkedMapOf<String, OpmlFeed>()
    var duplicates = 0
    var unsupported = 0
    for (outline in body.getElementsByTag("outline")) {
        val website = extension != null && outline.attr("type") == "link" && outline.hasAttr("${extension}selector")
        if (!website && !outline.hasAttr("xmlUrl")) continue
        val url = httpsUrl(outline.attr(if (website) "url" else "xmlUrl"))
        val selector = if (website) outline.attr("${extension}selector").trim().takeIf(::isWebsiteSelector) else null
        val watch = if (website && outline.hasAttr("${extension}watch")) outline.attr("${extension}watch") else null
        if (url == null || (website && selector == null) || (watch != null && decodeWebsiteWatch(watch) == null)) {
            unsupported++
            continue
        }
        if (url in feeds) {
            duplicates++
            continue
        }
        val title = outline.attr("title").trim().ifEmpty { outline.attr("text").trim() }
        val homeUrl = if (website) url else webUrl(outline.attr("htmlUrl"))
        val keywordFilter = extension?.let { readOpmlFilter(outline, it) }
        val queueFilters = if (extension == null) emptyMap() else buildMap {
            for (queue in outline.children().filter { it.tagName() == "${extension}queue" }) {
                val name = opmlQueueName(queue) ?: continue
                put(name, if (keywordFilter == null) readOpmlFilter(queue, extension) else null)
            }
        }
        feeds[url] = OpmlFeed(
            url, title.ifEmpty { URI(url).host }.take(500), homeUrl, selector, watch,
            extension != null && outline.attr("${extension}favorite") == "true", keywordFilter, queueFilters,
            when (extension?.let { outline.attr("${it}notifications") }) {
                "on" -> FeedNotificationMode.On
                "off" -> FeedNotificationMode.Off
                else -> FeedNotificationMode.Default
            },
        )
    }
    return OpmlContents(feeds.values.toList(), duplicates, unsupported, queues.distinct())
}

internal fun encodeOpml(feeds: List<OpmlFeed>, queues: List<String> = emptyList()): ByteArray {
    val document = Document("").parser(Parser.xmlParser())
    document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
        .charset(Charsets.UTF_8)
    document.appendChild(XmlDeclaration("xml", false).attr("version", "1.0").attr("encoding", "UTF-8"))
    val root = document.appendElement("opml").attr("version", "2.0").attr("xmlns:qarry", QARRY_OPML_NAMESPACE)
    val head = root.appendElement("head")
    head.appendElement("title").text("Q-arry")
    queues.forEach { name -> head.appendElement("qarry:queue").attr("name", name.replace(invalidXmlText, "")) }
    val body = root.appendElement("body")
    for (feed in feeds) {
        val title = feed.title.replace(invalidXmlText, "").ifBlank { feed.url }
        val outline = body.appendElement("outline").attr("text", title).attr("title", title)
        if (feed.htmlItemSelector != null) {
            outline.attr("type", "link").attr("url", feed.url).attr("qarry:selector", feed.htmlItemSelector)
            feed.htmlWatch?.let { outline.attr("qarry:watch", it) }
        } else {
            outline.attr("type", "rss").attr("xmlUrl", feed.url)
            feed.homeUrl?.let { outline.attr("htmlUrl", it) }
            val categories = feed.queueFilters.keys.filter { ',' !in it && '/' !in it && !invalidXmlText.containsMatchIn(it) }
            if (categories.isNotEmpty()) outline.attr("category", categories.joinToString(",") { "/$it" })
        }
        if (feed.isFavorite) outline.attr("qarry:favorite", "true")
        when (feed.notifications) {
            FeedNotificationMode.On -> outline.attr("qarry:notifications", "on")
            FeedNotificationMode.Off -> outline.attr("qarry:notifications", "off")
            FeedNotificationMode.Default -> Unit
        }
        feed.keywordFilter?.let { outline.appendOpmlFilter(it) }
        feed.queueFilters.forEach { (name, filter) ->
            val queue = outline.appendElement("qarry:queue").attr("name", name.replace(invalidXmlText, ""))
            filter?.let { queue.appendOpmlFilter(it) }
        }
    }
    return document.outerHtml().toByteArray(Charsets.UTF_8).also {
        if (it.size > MAX_OPML_BYTES) throw OpmlFormatException("エクスポートするフィード一覧が大きすぎます。")
    }
}

private fun opmlQueueName(queue: Element): String? =
    queue.attr("name").takeIf { it.isNotBlank() && it.length <= 80 && !invalidXmlText.containsMatchIn(it) }

private fun readOpmlFilter(parent: Element, prefix: String): KeywordFilter? {
    val filter = parent.children().firstOrNull { it.tagName() == "${prefix}filter" } ?: return null
    val mode = KeywordFilterMode.entries.firstOrNull { it.name == filter.attr("mode") } ?: return null
    val terms = filter.children().filter { it.tagName() == "${prefix}term" }
        .map { it.attr("value") }.filter(String::isNotBlank).distinct()
    return terms.takeIf { it.isNotEmpty() }?.let { KeywordFilter(mode, it) }
}

private fun Element.appendOpmlFilter(rule: KeywordFilter) {
    val filter = appendElement("qarry:filter").attr("mode", rule.mode.name)
    rule.keywords.forEach { term -> filter.appendElement("qarry:term").attr("value", term.replace(invalidXmlText, "")) }
}

private fun isWebsiteSelector(selector: String): Boolean {
    if (selector.isBlank() || selector.length > 4_096) return false
    // Site scopes use simple selectors and numerical :nth-child. Exclude regex and nested pseudo-selectors.
    if (selector.replace(selectorEscapes, "").any { it in "():[]" }) return false
    return try {
        QueryParser.parse(selector)
        true
    } catch (_: SelectorParseException) {
        false
    }
}

private const val MAX_OPML_BYTES = 2 * 1024 * 1024
private const val QARRY_OPML_NAMESPACE = "https://github.com/sasakiy84/q-arry-android/opml"
private val selectorEscapes = Regex("""\\[^\r\n]|:nth-child\([0-9]+\)""")
private val invalidXmlText = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]")
