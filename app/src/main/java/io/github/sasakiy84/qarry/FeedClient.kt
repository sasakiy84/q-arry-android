package io.github.sasakiy84.qarry

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

data class FeedDocument(
    val url: String,
    val title: String,
    val home: String?,
    val articles: List<FeedArticle>,
    val etag: String? = null,
    val modified: String? = null,
    val htmlItemSelector: String? = null,
)

data class FeedArticle(
    val key: String,
    val title: String?,
    val url: String?,
    val body: String?,
    val published: Long?,
    val thumbnailUrl: String? = null,
)

class FeedRequestException(message: String, cause: Throwable? = null) : IOException(message, cause)

class FeedClient(
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {
    fun discover(input: String): List<FeedDocument> = discover(input, preview = false).feeds

    internal fun discoverForRegistration(input: String): FeedDiscovery = discover(input, preview = true)

    private fun discover(input: String, preview: Boolean): FeedDiscovery {
        val value = input.trim()
        if (value.isEmpty()) throw FeedRequestException("URLを入力してください。")
        val initialUrl = webUrl(if (schemePrefix.containsMatchIn(value)) value else "https://$value")
            ?: throw FeedRequestException("HTTPSのURLを入力してください。")
        val page = fetch(initialUrl)
        parse(page)?.let { return FeedDiscovery(feeds = listOf(it)) }

        val html = page.parseHtml()
        val advertised = html.select("head link[href][rel]").mapNotNull { link ->
            val relations = link.attr("rel").split(Regex("\\s+"))
            val mime = link.attr("type").substringBefore(';').lowercase(Locale.ROOT)
            if (relations.none { it.equals("alternate", ignoreCase = true) } ||
                mime !in feedTypes
            ) return@mapNotNull null
            webUrl(link.absUrl("href"))
        }
        val candidates = advertised.distinct().take(MAX_ADVERTISED_FEEDS)
        val discovered = candidates.mapNotNull { tryCandidate(it) }.distinctBy { it.url }
        if (discovered.isNotEmpty()) return FeedDiscovery(feeds = discovered)

        val localPaths = listOf("feed/", "rss.xml", "atom.xml", "index.xml", "feed.xml", "rss")
        val origin = URI(page.url).let { "${it.scheme}://${it.rawAuthority}/" }
        val sitePath = URI(page.url).rawPath.trimEnd('/')
        val nearPage = if (sitePath.isNotEmpty() && '.' !in sitePath.substringAfterLast('/')) {
            listOf("$sitePath/feed/", "$sitePath/rss")
        } else {
            emptyList()
        }
        for (path in nearPage + localPaths) {
            val candidate = resolveUrl(origin, path) ?: continue
            if (candidate == page.url || candidate in candidates) continue
            tryCandidate(candidate)?.let { return FeedDiscovery(feeds = listOf(it)) }
        }
        val website = if (preview) {
            websiteScopes(html, page.url, page.etag, page.modified)?.let { FeedDiscovery(website = it) }
        } else {
            extractHtmlFeed(html, page.url)
                ?.let { FeedDiscovery(feeds = listOf(it.copy(etag = page.etag, modified = page.modified))) }
        }
        return website ?: throw FeedRequestException("フィードまたは記事一覧を見つけられませんでした。")
    }

    fun refresh(url: String, etag: String?, modified: String?, htmlItemSelector: String? = null): FeedDocument? {
        val response = fetch(url, etag, modified)
        if (response.notModified) return null
        if (htmlItemSelector != null) {
            return extractHtmlFeed(response.parseHtml(), response.url, htmlItemSelector)
                ?.copy(etag = response.etag, modified = response.modified)
                ?: throw FeedRequestException("登録時に選ばれた記事一覧を取得できませんでした。")
        }
        return parse(response) ?: throw FeedRequestException("RSS・Atomとして読み込めませんでした。")
    }

    fun articleThumbnail(url: String): String? {
        val articleUrl = httpsUrl(url) ?: return null
        val head = fetch(articleUrl, articleMetadata = true).parseHtml().head()
        val images = head.select("meta[content]")
        for (key in articleImageKeys) {
            for (image in images) {
                if (image.attr("property").equals(key, true) || image.attr("name").equals(key, true)) {
                    httpsUrl(image.absUrl("content"))?.let { return it }
                }
            }
        }
        return head.select("link[rel=image_src][href]")
            .firstNotNullOfOrNull { httpsUrl(it.absUrl("href")) }
    }

    private fun tryCandidate(url: String): FeedDocument? {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        return try {
            parse(fetch(url))
        } catch (_: FeedRequestException) {
            null
        }
    }

    private fun parse(response: FeedResponse): FeedDocument? =
        parseFeed(response.bytes, response.url, response.charset)?.copy(
            etag = response.etag,
            modified = response.modified,
        )

    private fun fetch(
        url: String,
        etag: String? = null,
        modified: String? = null,
        articleMetadata: Boolean = false,
    ): FeedResponse {
        var address = url
        var conditional = true
        val addresses = mutableSetOf<String>()
        repeat(6) {
            if (!addresses.add(address)) throw FeedRequestException("転送がループしています。")
            if (!address.startsWith("https://")) {
                throw FeedRequestException("サイトに接続するにはHTTPSのURLが必要です。")
            }
            val connection = openConnection(address).apply {
                instanceFollowRedirects = false
                connectTimeout = 5_000
                readTimeout = 7_000
                setRequestProperty("User-Agent", "Q-arry/0.1 (Android feed reader)")
                setRequestProperty(
                    "Accept",
                    if (articleMetadata) "text/html, application/xhtml+xml"
                    else "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*;q=0.5",
                )
                if (conditional) {
                    etag?.let { setRequestProperty("If-None-Match", it) }
                    modified?.let { setRequestProperty("If-Modified-Since", it) }
                }
            }
            try {
                when (val status = connection.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val next = resolveUrl(address, connection.getHeaderField("Location").orEmpty())
                            ?: throw FeedRequestException("転送先のURLを読み込めませんでした。")
                        if (URI(address).rawAuthority != URI(next).rawAuthority ||
                            URI(address).scheme != URI(next).scheme
                        ) conditional = false
                        address = next
                    }
                    HttpURLConnection.HTTP_NOT_MODIFIED -> {
                        if (!conditional || (etag == null && modified == null)) {
                            throw FeedRequestException("更新確認の応答を読み込めませんでした。")
                        }
                        return FeedResponse(address, notModified = true)
                    }
                    in 200..299 -> {
                        val limit = if (articleMetadata) MAX_ARTICLE_METADATA_BYTES else MAX_FEED_BYTES + 1
                        val bytes = connection.inputStream.use { it.readNBytes(limit) }
                        if (bytes.size > MAX_FEED_BYTES) throw FeedRequestException("取得したデータが大きすぎます。")
                        val charset = connection.contentType?.let { contentType ->
                            charsetPattern.find(contentType)?.groupValues?.get(1)?.let {
                                runCatching { Charset.forName(it).name() }.getOrNull()
                            }
                        }
                        return FeedResponse(
                            url = address,
                            bytes = bytes,
                            charset = charset,
                            etag = connection.getHeaderField("ETag"),
                            modified = connection.getHeaderField("Last-Modified"),
                        )
                    }
                    else -> throw FeedRequestException("フィードを取得できませんでした（HTTP $status）。")
                }
            } catch (error: FeedRequestException) {
                throw error
            } catch (error: IOException) {
                throw FeedRequestException("サイトに接続できませんでした。", error)
            } finally {
                connection.disconnect()
            }
        }
        throw FeedRequestException("URLの転送回数が多すぎます。")
    }
}

private data class FeedResponse(
    val url: String,
    val bytes: ByteArray = ByteArray(0),
    val charset: String? = null,
    val etag: String? = null,
    val modified: String? = null,
    val notModified: Boolean = false,
) {
    fun parseHtml(): Document = Jsoup.parse(ByteArrayInputStream(bytes), charset, url)
}

private const val MAX_FEED_BYTES = 8 * 1024 * 1024
private const val MAX_ARTICLE_METADATA_BYTES = 128 * 1024
private const val MAX_ADVERTISED_FEEDS = 6
private val articleImageKeys = listOf("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src")
private val schemePrefix = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
private val charsetPattern = Regex("""(?i)charset\s*=\s*["']?([^\s;"']+)""")
private val feedTypes = setOf("application/rss+xml", "application/atom+xml", "application/rdf+xml", "text/rss+xml")
private val rssDateFormats = listOf(
    DateTimeFormatter.RFC_1123_DATE_TIME,
    DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendPattern("[EEE, ]d MMM [uuuu][uu] HH:mm[:ss] z").toFormatter(Locale.US),
)

fun webUrl(value: String): String? {
    val parsed = runCatching { URI(value.trim().replace(" ", "%20")).normalize() }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
    val host = parsed.host?.lowercase(Locale.ROOT) ?: return null
    if (scheme !in setOf("http", "https") || parsed.rawUserInfo != null) return null
    if (parsed.port != -1 && parsed.port !in 1..65535) return null
    val authority = if (parsed.port == -1 || (scheme == "https" && parsed.port == 443) ||
        (scheme == "http" && parsed.port == 80)
    ) host else "$host:${parsed.port}"
    val path = parsed.rawPath.ifEmpty { "/" }
    val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
    return URI("$scheme://$authority$path$query").toASCIIString()
}

internal fun httpsUrl(value: String?): String? =
    value?.let(::webUrl)?.takeIf { it.startsWith("https://") }

internal fun resolveUrl(base: String, value: String): String? {
    if (value.isBlank()) return null
    val target = runCatching {
        if (value.trimStart().startsWith("?")) {
            base.substringBefore('?').substringBefore('#') + value.trim()
        } else {
            URI(base).resolve(value.trim().replace(" ", "%20")).toString()
        }
    }.getOrNull() ?: return null
    return webUrl(target)
}

fun parseFeed(bytes: ByteArray, url: String, charset: String? = null): FeedDocument? {
    val xml = Jsoup.parse(ByteArrayInputStream(bytes), charset, url, Parser.xmlParser())
    val root = xml.children().firstOrNull() ?: return null
    val atom = root.localName() == "feed"
    val rdf = root.localName() == "rdf"
    if (!atom && !rdf && root.localName() != "rss") return null
    val info = if (atom) root else root.child("channel") ?: return null
    val base = info.feedBase(url)
    val home = if (atom) info.atomLink(base) else resolveUrl(base, info.child("link")?.wholeText().orEmpty())
    val entries = (if (rdf) root else info).children()
        .asSequence()
        .filter { it.localName() == if (atom) "entry" else "item" }
        .take(500)
        .mapNotNull { entry ->
            val entryBase = entry.feedBase(url)
            val entryLink = if (atom) entry.atomLink(entryBase) else {
                resolveUrl(entryBase, entry.child("link")?.wholeText().orEmpty())
                    ?: entry.child("guid")?.takeUnless { it.attr("isPermaLink").equals("false", true) }
                        ?.wholeText()?.let(::webUrl)
            }
            val title = entry.child("title")?.feedText().orEmpty()
            val body = listOf("content:encoded", "content", "description", "summary")
                .firstNotNullOfOrNull { name ->
                    val field = if (':' in name) {
                        entry.children().firstOrNull { it.tagName().equals(name, true) }
                    } else {
                        entry.child(name)
                    }
                    field
                        ?.feedText()?.takeIf(String::isNotBlank)
                }.orEmpty().take(24_000)
            val dateText = listOf("published", "pubDate", "date", "updated")
                .firstNotNullOfOrNull { entry.child(it)?.wholeText()?.trim()?.takeIf(String::isNotEmpty) }
            val externalId = entry.child(if (atom) "id" else "guid")?.wholeText()?.trim()?.takeIf(String::isNotEmpty)
            if (title.isBlank() && body.isBlank() && entryLink == null) return@mapNotNull null
            val stableKey = externalId ?: entryLink ?: "$title\u0000${dateText ?: body}"
            FeedArticle(
                key = MessageDigest.getInstance("SHA-256").digest(stableKey.toByteArray()).toHexString(),
                title = title.ifEmpty { "無題の記事" }.take(500),
                url = entryLink,
                body = body,
                published = feedDate(dateText),
                thumbnailUrl = entry.feedThumbnail(url),
            )
        }.distinctBy { it.key }.toList()
    return FeedDocument(
        url = url,
        title = info.child("title")?.feedText()?.take(500)?.takeIf(String::isNotBlank) ?: URI(url).host,
        home = home,
        articles = entries,
    )
}

private fun Element.localName(): String = tagName().substringAfter(':').lowercase(Locale.ROOT)

private fun Element.child(name: String): Element? =
    children().firstOrNull { it.localName().equals(name, ignoreCase = true) }

private fun Element.feedThumbnail(base: String): String? {
    val media = children().toList() + children()
        .filter { it.tagName().equals("media:group", true) }
        .flatMap { it.children() }
    return media.firstNotNullOfOrNull { element ->
        val tag = element.tagName().lowercase(Locale.ROOT)
        val imageType = element.attr("type").startsWith("image/", ignoreCase = true)
        val imageUrl = when {
            tag == "media:thumbnail" -> element.attr("url")
            tag == "media:content" && (imageType || element.attr("medium").equals("image", true)) -> element.attr("url")
            tag == "enclosure" && imageType -> element.attr("url")
            element.localName() == "link" && element.attr("rel") == "enclosure" && imageType -> element.attr("href")
            else -> ""
        }
        resolveUrl(element.feedBase(base), imageUrl)?.let(::httpsUrl)
    }
}

private fun Element.feedBase(base: String): String =
    parents().asReversed().plus(this).fold(base) { address, element ->
        resolveUrl(address, element.attr("xml:base")) ?: address
    }

private fun Element.atomLink(base: String): String? = children()
    .asSequence()
    .filter { it.localName() == "link" && it.attr("rel").ifEmpty { "alternate" } == "alternate" }
    .mapNotNull { resolveUrl(resolveUrl(base, it.attr("xml:base")) ?: base, it.attr("href")) }
    .firstOrNull()

private fun Element.feedText(): String {
    val value = if (children().isEmpty()) wholeText() else html()
    if ('<' !in value) return value.trim()
    val html = Jsoup.parseBodyFragment(value)
    html.select("script, style, noscript").remove()
    html.select("p, div, li, h1, h2, h3, h4, blockquote, pre")
        .forEach { it.appendChild(TextNode("\n\n")) }
    return html.body().wholeText().trim().replace(Regex("\\n(?:[ \\t]*\\n){2,}"), "\n\n")
}

private fun feedDate(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: rssDateFormats.firstNotNullOfOrNull { format ->
            runCatching { ZonedDateTime.parse(value, format).toInstant().toEpochMilli() }.getOrNull()
        } ?: runCatching { LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrNull()
}
