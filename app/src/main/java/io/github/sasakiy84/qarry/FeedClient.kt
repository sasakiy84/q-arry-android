package io.github.sasakiy84.qarry

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import javax.net.ssl.SSLException

data class FeedDocument(
    val url: String,
    val title: String,
    val home: String?,
    val articles: List<FeedArticle>,
    val etag: String? = null,
    val modified: String? = null,
    val htmlItemSelector: String? = null,
    val htmlWatch: String? = null,
    val observedAt: Long? = null,
)

data class FeedArticle(
    val key: String,
    val title: String?,
    val url: String?,
    val body: String?,
    val published: Long?,
    val thumbnailUrl: String? = null,
    val updatedAt: Long? = null,
    val publishedTime: Long? = published,
)

internal data class ArticleMetadata(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val updatedAt: Long? = null,
    val publishedTime: Long? = null,
)

class FeedRequestException(message: String, cause: Throwable? = null, val httpStatus: Int? = null) : IOException(message, cause)

class FeedClient(
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {
    private val archivePolicies = LinkedHashMap<String, Pair<Long, ArchiveRobots>>()

    fun discover(input: String): List<FeedDocument> = discover(input, preview = false).feeds

    internal fun discoverForRegistration(input: String): FeedDiscovery = discover(input, preview = true)

    internal fun inspectWebsite(url: String): WebsiteScopes {
        val page = fetch(url)
        return websiteScopes(page.parseHtml(), page.url, page.etag, page.modified)
            ?: throw FeedRequestException("選び直せる記事一覧が見つかりませんでした。")
    }

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
            extractHtmlFeed(html, page.url)?.let { feed ->
                val matches = matchedHtmlArticles(html.select(requireNotNull(feed.htmlItemSelector)), page.url)
                val watch = encodeWebsiteWatch(watchFor(matches))
                FeedDiscovery(feeds = listOf(feed.copy(
                    etag = page.etag, modified = page.modified, htmlWatch = watch, observedAt = System.currentTimeMillis(),
                )))
            }
        }
        return website ?: throw FeedRequestException("フィードまたは記事一覧を見つけられませんでした。")
    }

    fun refresh(
        url: String,
        etag: String?,
        modified: String?,
        htmlItemSelector: String? = null,
        htmlWatch: String? = null,
        previousKeys: Set<String> = emptySet(),
    ): FeedDocument? {
        val needsVerification = htmlWatch == null && htmlItemSelector?.contains(":nth-child(") == true
        val response = if (needsVerification) fetch(url) else fetch(url, etag, modified)
        if (response.notModified) return null
        if (htmlItemSelector != null) {
            return refreshWebsiteDocument(response.parseHtml(), response.url, htmlItemSelector, htmlWatch, previousKeys)
                ?.copy(etag = response.etag, modified = response.modified, observedAt = System.currentTimeMillis())
                ?: throw FeedRequestException(WEBSITE_SCOPE_CHANGED)
        }
        return parse(response) ?: throw FeedRequestException("RSS・Atomとして読み込めませんでした。")
    }

    internal fun articleMetadata(url: String): ArticleMetadata? {
        val articleUrl = httpsUrl(url) ?: return null
        val head = fetch(articleUrl, articleMetadata = true).parseHtml().head()
        val metadata = head.select("meta[content]")
        fun <T : Any> firstValue(keys: List<String>, value: (Element) -> T?): T? = keys.firstNotNullOfOrNull { key ->
            metadata.firstNotNullOfOrNull { meta ->
                if (meta.attr("property").equals(key, true) || meta.attr("name").equals(key, true)) value(meta) else null
            }
        }

        fun textValue(meta: Element): String? = meta.attr("content").trim().takeIf(String::isNotEmpty)

        val title = firstValue(articleTitleKeys, ::textValue)
        val description = firstValue(articleDescriptionKeys, ::textValue)
        val imageUrl = firstValue(articleImageKeys) { meta ->
            meta.attr("content").takeIf(String::isNotBlank)?.let { httpsUrl(meta.absUrl("content")) }
        } ?: head.select("link[rel=image_src][href]")
            .firstNotNullOfOrNull { httpsUrl(it.absUrl("href")) }
        val updatedAt = firstValue(listOf("article:modified_time", "og:updated_time", "dateModified")) {
            sourceTimestamp(it.attr("content"))
        }
        val publishedTime = firstValue(listOf("article:published_time", "datePublished")) { sourceTimestamp(it.attr("content")) }
        return if (title != null || description != null || imageUrl != null || updatedAt != null || publishedTime != null) {
            ArticleMetadata(title, description, imageUrl, updatedAt, publishedTime)
        } else {
            null
        }
    }

    internal fun articleText(url: String): String? {
        val address = httpsUrl(url) ?: return null
        val page = fetch(address, archiveArticle = true)
        return if (page.archiveAllowed) extractArticleText(page.parseHtml()) else null
    }

    private fun robotsAllowArchive(address: String): Boolean {
        val uri = URI(address)
        val origin = "${uri.scheme}://${uri.rawAuthority}"
        val now = System.currentTimeMillis()
        val cached = synchronized(archivePolicies) { archivePolicies[origin] }
        val rules = if (cached != null && cached.first > now) cached.second else {
            val policy = try {
                ArchiveRobots.parse(fetch("$origin/robots.txt", robots = true).bytes.toString(Charsets.UTF_8))
            } catch (error: FeedRequestException) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                ArchiveRobots.parse(if (error.httpStatus in setOf(404, 410)) "" else "User-agent: *\nDisallow: /")
            }
            synchronized(archivePolicies) {
                if (archivePolicies.size >= 128) archivePolicies.remove(archivePolicies.keys.first())
                archivePolicies[origin] = now + 60 * 60 * 1000L to policy
            }
            policy
        }
        val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        return rules.allows(path)
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
            observedAt = System.currentTimeMillis(),
        )

    private fun fetch(
        url: String,
        etag: String? = null,
        modified: String? = null,
        articleMetadata: Boolean = false,
        archiveArticle: Boolean = false,
        robots: Boolean = false,
    ): FeedResponse {
        try {
            return fetchFollowingRedirects(url, etag, modified, articleMetadata, archiveArticle, robots)
        } catch (error: FeedRequestException) {
            throw error
        } catch (error: IOException) {
            val message = when (error) {
                is UnknownHostException -> "接続先を見つけられませんでした。"
                is SocketTimeoutException -> "サイトへの接続がタイムアウトしました。"
                is SSLException -> "サイトとの安全な接続を確立できませんでした。"
                else -> "サイトに接続できませんでした。"
            }
            throw FeedRequestException(message, error)
        } catch (error: SecurityException) {
            throw FeedRequestException("サイトへの接続が許可されていません。", error)
        }
    }

    private fun fetchFollowingRedirects(
        url: String,
        etag: String?,
        modified: String?,
        articleMetadata: Boolean,
        archiveArticle: Boolean,
        robots: Boolean,
    ): FeedResponse {
        var address = url
        var conditional = true
        val addresses = mutableSetOf<String>()
        repeat(6) {
            if (!addresses.add(address)) throw FeedRequestException("転送がループしています。")
            if (!address.startsWith("https://")) {
                throw FeedRequestException("サイトに接続するにはHTTPSのURLが必要です。")
            }
            if (archiveArticle && !robotsAllowArchive(address)) return FeedResponse(address, archiveAllowed = false)
            val connection = openConnection(address)
            try {
                connection.apply {
                    instanceFollowRedirects = false
                    connectTimeout = 5_000
                    readTimeout = 7_000
                    setRequestProperty("User-Agent", "Q-arry/0.1 (Android feed reader)")
                    setRequestProperty(
                        "Accept",
                        if (robots) "text/plain, */*;q=0.1"
                        else if (articleMetadata || archiveArticle) "text/html, application/xhtml+xml"
                        else "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*;q=0.5",
                    )
                    if (conditional) {
                        etag?.let { setRequestProperty("If-None-Match", it) }
                        modified?.let { setRequestProperty("If-Modified-Since", it) }
                    }
                }
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
                        val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                        if (archiveArticle && contentType != null && contentType !in articleContentTypes) {
                            return FeedResponse(address, archiveAllowed = false)
                        }
                        val noArchive = archiveArticle && connection.headerFields.any { (key, values) ->
                            key.equals("X-Robots-Tag", true) && values.any { noArchiveDirective.containsMatchIn(it) }
                        }
                        if (noArchive) return FeedResponse(address, archiveAllowed = false)
                        val limit = when {
                            robots -> 512 * 1024
                            articleMetadata -> MAX_ARTICLE_METADATA_BYTES
                            archiveArticle -> MAX_ARCHIVE_PAGE_BYTES + 1
                            else -> MAX_FEED_BYTES + 1
                        }
                        val bytes = connection.inputStream.use { it.readNBytes(limit) }
                        if (bytes.size > (if (archiveArticle) MAX_ARCHIVE_PAGE_BYTES else MAX_FEED_BYTES)) {
                            throw FeedRequestException("取得したデータが大きすぎます。")
                        }
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
                    else -> throw FeedRequestException("フィードを取得できませんでした（HTTP $status）。", httpStatus = status)
                }
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
    val archiveAllowed: Boolean = true,
) {
    fun parseHtml(): Document = Jsoup.parse(ByteArrayInputStream(bytes), charset, url)
}

private const val MAX_FEED_BYTES = 8 * 1024 * 1024
private const val MAX_ARTICLE_METADATA_BYTES = 128 * 1024
private const val MAX_ARCHIVE_PAGE_BYTES = 2 * 1024 * 1024
private val articleContentTypes = setOf("text/html", "application/xhtml+xml")
private val noArchiveDirective = Regex("""\bnoarchive\b""", RegexOption.IGNORE_CASE)
private const val MAX_ADVERTISED_FEEDS = 6
private val articleTitleKeys = listOf("og:title", "twitter:title")
private val articleDescriptionKeys = listOf("og:description", "twitter:description")
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
            val updatedAt = listOf("updated", "modified", "dateModified")
                .firstNotNullOfOrNull { sourceTimestamp(entry.child(it)?.wholeText()) }
            val publishedTime = listOf("published", "pubDate", "date")
                .firstNotNullOfOrNull { sourceTimestamp(entry.child(it)?.wholeText()) }
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
                updatedAt = updatedAt,
                publishedTime = publishedTime,
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

private fun feedDate(value: String?): Long? = sourceTimestamp(value) ?: value?.let {
    runCatching { LocalDate.parse(it).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
}

internal fun sourceTimestamp(value: String?): Long? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val iso = if (text.length > 10 && text[10] == ' ') text.replaceRange(10, 11, "T") else text
    return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        ?: rssDateFormats.firstNotNullOfOrNull { format ->
            runCatching { ZonedDateTime.parse(text, format).toInstant().toEpochMilli() }.getOrNull()
        }
}
