package io.github.sasakiy84.qarry

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.io.InputStream
import java.net.URI

internal data class OpmlFeed(val url: String, val title: String)

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

    val feeds = linkedMapOf<String, OpmlFeed>()
    var duplicates = 0
    var unsupported = 0
    for (outline in body.getElementsByTag("outline")) {
        if (!outline.hasAttr("xmlUrl")) continue
        val url = httpsUrl(outline.attr("xmlUrl"))
        if (url == null) {
            unsupported++
            continue
        }
        if (url in feeds) {
            duplicates++
            continue
        }
        val title = outline.attr("title").trim().ifEmpty { outline.attr("text").trim() }
        feeds[url] = OpmlFeed(url, title.ifEmpty { URI(url).host }.take(500))
    }
    return OpmlContents(feeds.values.toList(), duplicates, unsupported)
}

private const val MAX_OPML_BYTES = 2 * 1024 * 1024
