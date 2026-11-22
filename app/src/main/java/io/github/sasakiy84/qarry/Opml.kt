package io.github.sasakiy84.qarry

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
)

internal data class OpmlContents(
    val feeds: List<OpmlFeed>,
    val duplicateCount: Int,
    val unsupportedCount: Int,
)

internal class OpmlFormatException(message: String) : IOException(message)

internal fun readOpml(input: InputStream): OpmlContents {
    val bytes = input.readNBytes(MAX_OPML_BYTES + 1)
    if (bytes.size > MAX_OPML_BYTES) throw OpmlFormatException("OPMLファイルが大きすぎます。")
    val document = Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
    val opml = document.children().singleOrNull()?.takeIf { it.normalName() == "opml" }
    val body = opml?.children()?.firstOrNull { it.normalName() == "body" }
        ?: throw OpmlFormatException("OPMLファイルを選んでください。")
    val qarryExtension = opml.attr("xmlns:qarry") == QARRY_OPML_NAMESPACE

    val feeds = linkedMapOf<String, OpmlFeed>()
    var duplicates = 0
    var unsupported = 0
    for (outline in body.getElementsByTag("outline")) {
        val website = qarryExtension && outline.attr("type") == "link" && outline.hasAttr(WEBSITE_SELECTOR_ATTRIBUTE)
        if (!website && !outline.hasAttr("xmlUrl")) continue
        val url = httpsUrl(outline.attr(if (website) "url" else "xmlUrl"))
        val selector = if (website) outline.attr(WEBSITE_SELECTOR_ATTRIBUTE).trim().takeIf(::isWebsiteSelector) else null
        val watch = if (website && outline.hasAttr(WEBSITE_WATCH_ATTRIBUTE)) outline.attr(WEBSITE_WATCH_ATTRIBUTE) else null
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
        feeds[url] = OpmlFeed(url, title.ifEmpty { URI(url).host }.take(500), homeUrl, selector, watch)
    }
    return OpmlContents(feeds.values.toList(), duplicates, unsupported)
}

internal fun encodeOpml(feeds: List<OpmlFeed>): ByteArray {
    val document = Document("")
    document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
        .charset(Charsets.UTF_8)
    document.appendChild(XmlDeclaration("xml", false).attr("version", "1.0").attr("encoding", "UTF-8"))
    val root = document.appendElement("opml").attr("version", "2.0").attr("xmlns:qarry", QARRY_OPML_NAMESPACE)
    root.appendElement("head").appendElement("title").text("Q-arry")
    val body = root.appendElement("body")
    for (feed in feeds) {
        val title = feed.title.replace(invalidXmlText, "").ifBlank { feed.url }
        val outline = body.appendElement("outline").attr("text", title).attr("title", title)
        if (feed.htmlItemSelector != null) {
            outline.attr("type", "link").attr("url", feed.url).attr(WEBSITE_SELECTOR_ATTRIBUTE, feed.htmlItemSelector)
            feed.htmlWatch?.let { outline.attr(WEBSITE_WATCH_ATTRIBUTE, it) }
        } else {
            outline.attr("type", "rss").attr("xmlUrl", feed.url)
            feed.homeUrl?.let { outline.attr("htmlUrl", it) }
        }
    }
    return document.outerHtml().toByteArray(Charsets.UTF_8).also {
        if (it.size > MAX_OPML_BYTES) throw OpmlFormatException("エクスポートするフィード一覧が大きすぎます。")
    }
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
private const val WEBSITE_SELECTOR_ATTRIBUTE = "qarry:selector"
private const val WEBSITE_WATCH_ATTRIBUTE = "qarry:watch"
private val selectorEscapes = Regex("""\\[^\r\n]|:nth-child\([0-9]+\)""")
private val invalidXmlText = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]")
