package io.github.sasakiy84.qarry

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.net.URI

internal data class ArchivedArticleText(val body: String, val format: String)
internal data class ArchivedLink(val start: Int, val end: Int, val url: String)
internal data class ArchivedParagraph(val text: String, val headingLevel: Int = 0, val links: List<ArchivedLink> = emptyList())

internal fun extractArticleText(document: Document): ArchivedArticleText? {
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
    val blocks = candidates.mapNotNull { candidate ->
        val blocks = articleBlocks(candidate)
        val textLength = blocks.sumOf { it.paragraphText().length } + (blocks.size - 1) * 2
        if (textLength < 80) return@mapNotNull null
        val linkLength = candidate.select("a").sumOf { it.text().length }
        val linkFraction = linkLength.toDouble() / textLength
        if (linkFraction > 0.45 || candidate.select("article").size > 1) return@mapNotNull null
        val paragraphs = candidate.select("p").count { it.text().length >= 35 }.coerceAtMost(20)
        val explicit = candidate.normalName() == "article" || candidate.hasAttr("itemprop") ||
            candidate.hasClass("entry-content") || candidate.hasClass("post-content") || candidate.hasClass("article-body")
        if (!explicit && paragraphs == 0 && textLength < 360) return@mapNotNull null
        val score = textLength.coerceAtMost(12_000) * (1 - linkFraction) +
            paragraphs * 100 + if (explicit) 600 else 0
        blocks to score
    }.maxByOrNull { it.second }?.first ?: return null

    val text = StringBuilder()
    val format = JSONObject()
    var remainingLinks = 256
    for (block in blocks) {
        val available = MAX_ARCHIVE_CHARS - text.length - if (text.isEmpty()) 0 else 2
        if (available <= 0) break
        val paragraph = block.archivedParagraph(remainingLinks)
        val keptText = paragraph.text.take(available)
        if (keptText.isEmpty()) continue
        if (text.isNotEmpty()) text.append("\n\n")
        val links = paragraph.links.filter { it.end <= keptText.length }
        if (format.length() < 2048 && (paragraph.headingLevel > 0 || links.isNotEmpty())) {
            val spans = JSONArray()
            for (link in links) spans.put(JSONArray().put(link.start).put(link.end).put(link.url))
            format.put(text.length.toString(), JSONArray().put(paragraph.headingLevel).put(spans))
            remainingLinks -= links.size
        }
        text.append(keptText)
    }
    return ArchivedArticleText(text.toString(), format.toString())
}

private fun articleBlocks(candidate: Element): List<Element> {
    if (candidate.normalName() in articleBlockTags) return listOf(candidate)
    return candidate.select(articleBlockTags.joinToString(","))
        .filter { block ->
            block.parents().takeWhile { it !== candidate }.none { it.normalName() in articleBlockTags }
        }
        .filter { it.paragraphText().isNotBlank() }
        .ifEmpty { listOf(candidate) }
}

private fun Element.paragraphText(): String = if (normalName() == "pre") wholeText().trim() else text()

private fun Element.archivedParagraph(linkLimit: Int): ArchivedParagraph {
    val heading = articleHeadings.indexOf(normalName()) + 1
    if (linkLimit <= 0 || normalName() == "pre" || selectFirst("a[href]") == null) {
        return ArchivedParagraph(paragraphText(), heading)
    }
    val text = StringBuilder()
    val links = mutableListOf<ArchivedLink>()
    var anchorStart = 0
    fun space() {
        if (text.isNotEmpty() && text.last() != ' ') text.append(' ')
    }
    NodeTraversor.traverse(object : NodeVisitor {
        override fun head(node: Node, depth: Int) {
            when (node) {
                is TextNode -> for (character in node.wholeText) {
                    if (character in " \t\n\r\u000c\u00a0") space() else text.append(character)
                }
                is Element -> {
                    if (node.isBlock || node.normalName() == "br") space()
                    if (node.normalName() == "a") anchorStart = text.length
                }
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node !is Element) return
            if (node.normalName() == "a" && node.attr("href").isNotBlank() && links.size < linkLimit) {
                val url = archivedLinkUrl(node.absUrl("href"))
                val start = anchorStart + text.subSequence(anchorStart, text.length).takeWhile { it == ' ' }.length
                val end = if (text.lastOrNull() == ' ') text.length - 1 else text.length
                if (url != null && url.length <= 2048 && start < end) links += ArchivedLink(start, end, url)
            }
            if (node.isBlock) space()
        }
    }, this)
    return ArchivedParagraph(text.toString().trimEnd(' '), heading, links)
}

internal fun archivedLinkUrl(address: String): String? {
    val safe = httpsUrl(address) ?: return null
    val fragment = address.trim().substringAfter('#', "").replace(" ", "%20")
    return if (fragment.isEmpty()) safe else URI("$safe#$fragment").toASCIIString()
}

internal fun archivedParagraphs(body: String, format: String?): List<ArchivedParagraph> {
    val styles = try {
        format?.let(::JSONObject)
    } catch (_: JSONException) {
        null
    }
    var offset = 0
    return buildList {
        for (paragraph in body.split("\n\n")) {
            val style = styles?.optJSONArray(offset.toString())
            val spans = style?.optJSONArray(1)
            val heading = style?.optInt(0)?.coerceIn(0, 6) ?: 0
            val links = buildList {
                for (index in 0 until (spans?.length() ?: 0)) {
                    val span = spans?.optJSONArray(index) ?: continue
                    val start = span.optInt(0, -1)
                    val end = span.optInt(1, -1)
                    val url = archivedLinkUrl(span.optString(2))
                    if (start >= 0 && end > start && end <= paragraph.length && url != null) {
                        add(ArchivedLink(start, end, url))
                    }
                }
            }
            var start = 0
            for (chunk in paragraph.chunked(3500)) {
                val end = start + chunk.length
                val chunkLinks = links.filter { it.start < end && it.end > start }.map {
                    it.copy(start = maxOf(it.start, start) - start, end = minOf(it.end, end) - start)
                }
                add(ArchivedParagraph(chunk, heading, chunkLinks))
                start = end
            }
            offset += paragraph.length + 2
        }
    }
}

private val articleHeadings = listOf("h1", "h2", "h3", "h4", "h5", "h6")
private val articleBlockTags = articleHeadings + listOf("p", "pre", "li", "blockquote", "dt", "dd", "figcaption")
