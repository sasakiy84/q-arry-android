package io.github.sasakiy84.qarry

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.time.Instant
import kotlin.concurrent.thread

class FeedClientTest {
    @Test
    fun interruptionStopsAnInProgressResponseAndReleasesTheConnection() {
        var reads = 0
        var streamClosed = false
        var disconnected = false
        val client = FeedClient { url ->
            object : HttpURLConnection(URI(url).toURL()) {
                override fun connect(): Unit = Unit
                override fun disconnect() { disconnected = true }
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int = HTTP_OK
                override fun getInputStream(): InputStream = object : InputStream() {
                    override fun read(): Int = error("The response must be read in chunks")
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        reads++
                        if (reads > 1) return -1
                        Thread.currentThread().interrupt()
                        bytes[offset] = 60
                        return 1
                    }
                    override fun close() { streamClosed = true }
                }
            }
        }

        try {
            assertThrows(InterruptedException::class.java) {
                client.refresh("https://publisher.example/feed", null, null)
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals(1, reads)
        assertTrue(streamClosed)
        assertTrue(disconnected)
    }

    @Test
    fun rssAcceptsJapaneseAndHtmlPreservesStableKeysAndDropsDuplicateGuids() {
        val xml = """
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel>
              <title>日本語 &amp; RSS</title><link>/</link>
              <item><guid isPermaLink="false">abc</guid><title>記事</title><link>/posts/1</link>
                <pubDate>Wed, 11 Nov 2026 12:00:00 +0900</pubDate>
                <content:encoded><![CDATA[<p>本文</p><script>discard()</script>]]></content:encoded>
              </item>
              <item><guid isPermaLink="false">abc</guid><title>複製</title></item>
            </channel></rss>
        """.trimIndent()
        val feed = requireNotNull(parseFeed(xml.toByteArray(), "https://publisher.example/feed"))
        val article = feed.articles.single()
        assertEquals("日本語 & RSS", feed.title)
        assertEquals("https://publisher.example/posts/1", article.url)
        assertEquals("本文", article.body)
        assertEquals(Instant.parse("2026-11-11T03:00:00Z").toEpochMilli(), article.published)
        assertEquals(article.published, article.publishedTime)
        assertNull(article.updatedAt)
        val revision = parseFeed(xml.replace("<title>記事</title>", "<title>訂正</title>").toByteArray(), feed.url)
        assertEquals(article.key, revision?.articles?.single()?.key)
    }

    @Test
    fun atomFollowsXmlBaseAndUsesTheAlternateArticleLink() {
        val xml = """
            <atom:feed xmlns:atom="http://www.w3.org/2005/Atom" xml:base="https://publisher.example/blog/">
              <atom:title>Atom</atom:title>
              <atom:entry xml:base="posts/">
                <atom:id>urn:entry:1</atom:id><atom:title>Article</atom:title>
                <atom:link rel="self" href="1.xml"/><atom:link rel="alternate" href="1"/>
                <atom:updated>2026-11-11T13:25:00+09:00</atom:updated>
                <atom:summary type="html">&lt;p&gt;Summary&lt;/p&gt;</atom:summary>
              </atom:entry>
            </atom:feed>
        """.trimIndent()
        val article = requireNotNull(parseFeed(xml.toByteArray(), "https://origin.example/feed")).articles.single()
        assertEquals("https://publisher.example/blog/posts/1", article.url)
        assertEquals("Summary", article.body)
        assertEquals(Instant.parse("2026-11-11T04:25:00Z").toEpochMilli(), article.published)
        assertEquals(article.published, article.updatedAt)
        assertNull(article.publishedTime)
    }

    @Test
    fun publicationDateWithoutATimeDoesNotBecomeAMidnightTimestamp() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title>
              <entry><id>urn:entry:1</id><title>Article</title><published>2026-11-11</published>
                <updated>2026-11-11T13:25:00+09:00</updated></entry>
            </feed>
        """.trimIndent()
        val article = requireNotNull(parseFeed(xml.toByteArray(), "https://publisher.example/feed")).articles.single()
        assertNull(article.publishedTime)
        assertEquals(Instant.parse("2026-11-11T04:25:00Z").toEpochMilli(), article.updatedAt)
    }

    @Test
    fun rssOneReadsItemsOutsideTheChannel() {
        val xml = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <channel><title>RSS 1.0</title></channel>
              <item><title>Article</title><link>https://publisher.example/article</link></item>
            </rdf:RDF>
        """.trimIndent()
        assertEquals("Article", parseFeed(xml.toByteArray(), "https://publisher.example/feed")?.articles?.single()?.title)
    }

    @Test
    fun htmlAndNonHttpLinksAreNotTreatedAsFeedData() {
        val html = "<html><body><rss><channel><title>Not a feed</title></channel></rss></body></html>"
        assertNull(parseFeed(html.toByteArray(), "https://publisher.example/"))
        assertNull(webUrl("javascript:void(0)"))
        assertNull(webUrl("https://user:pass@publisher.example/"))
        assertEquals("https://publisher.example/feed?q=%2F", webUrl("HTTPS://publisher.example:443/feed?q=%2F#part"))
    }

    @Test
    fun rssUsesTheEntryMediaThumbnail() {
        val xml = """
            <rss xmlns:media="http://search.yahoo.com/mrss/"><channel><title>News</title>
              <item xml:base="/posts/"><title>Update</title><link>new</link>
                <enclosure type="audio/mpeg" url="audio.mp3"/>
                <media:group>
                  <media:thumbnail url="http://example.com/plain.jpg"/>
                  <media:thumbnail xml:base="images/" url="cover.jpg"/>
                </media:group>
              </item>
            </channel></rss>
        """.trimIndent()
        val feed = requireNotNull(parseFeed(xml.toByteArray(), "https://publisher.example/feed"))
        assertEquals("https://publisher.example/posts/images/cover.jpg", feed.articles.single().thumbnailUrl)
    }

    @Test
    fun articleMetadataPrefersOpenGraphAndResolvesImagesAgainstTheRedirectedPage() = withServer { server, address ->
        server["/old"] = { Response("", status = "302 Found", location = "/posts/story") }
        server["/posts/story"] = {
            Response(
                """<head>
                    <meta name="twitter:title" content="Twitter title">
                    <meta name="twitter:description" content="Twitter description">
                    <meta property="og:title" content=" 記事 &amp; ニュース ">
                    <meta property="og:description" content="記事ページの説明">
                    <meta property="og:image" content="https://publisher.example/general.jpg">
                    <meta property="og:image:secure_url" content="images/story.jpg">
                    <meta name="twitter:image" content="https://publisher.example/share.jpg">
                    <meta property="article:modified_time" content="2026-11-11T13:25:00+09:00">
                    <meta property="article:published_time" content="2026-11-10T11:00:00+09:00">
                </head>""".trimIndent() + " ".repeat(140_000),
                "text/html; charset=UTF-8",
            )
        }
        assertEquals(
            ArticleMetadata(
                "記事 & ニュース", "記事ページの説明", "https://publisher.example/posts/images/story.jpg",
                Instant.parse("2026-11-11T04:25:00Z").toEpochMilli(), Instant.parse("2026-11-10T02:00:00Z").toEpochMilli(),
            ),
            fixtureClient(address).articleMetadata("https://publisher.example/old"),
        )
    }

    @Test
    fun articleMetadataIgnoresMissingFieldsAndUsesSecureTwitterCardFields() = withServer { server, address ->
        server["/story"] = {
            Response(
                """<head><title>Ordinary page title</title>
                    <meta name="description" content="Ordinary description">
                    <meta property="og:title" content=" ">
                    <meta property="og:description" content="　">
                    <meta name="twitter:title" content="Twitter title">
                    <meta property="og:image:secure_url" content=" ">
                    <meta property="og:image" content="http://publisher.example/plain.jpg">
                    <meta name="twitter:image" content="//cdn.example/share.jpg">
                </head>""".trimIndent(),
                "text/html",
            )
        }
        server["/no-image"] = {
            Response(
                """<head><meta property="og:image" content="data:image/svg+xml,none"></head>
                    <body><img src="/logo.jpg"></body>""".trimIndent(),
                "text/html",
            )
        }
        val client = fixtureClient(address)
        assertEquals(
            ArticleMetadata("Twitter title", null, "https://cdn.example/share.jpg"),
            client.articleMetadata("https://publisher.example/story"),
        )
        assertNull(client.articleMetadata("https://publisher.example/no-image"))
        assertNull(client.articleMetadata("http://publisher.example/story"))
    }

    @Test
    fun websiteDiscoveryReturnsMultipleAdvertisedCandidates() = withServer { server, address ->
        server["/site/"] = {
            Response(
                "<head><base href='/feeds/'><link rel='alternate' type='application/rss+xml' href='one'>" +
                    "<link rel='alternate' type='application/rss+xml' href='http://publisher.example/plain'>" +
                    "<link rel='alternate' type='application/rss+xml' href='two'></head><body>Home</body>",
                "text/html; charset=UTF-8",
            )
        }
        server["/feeds/one"] = { Response(rss("One")) }
        server["/feeds/two"] = { Response(rss("Two")) }
        assertEquals(
            listOf("One", "Two"),
            fixtureClient(address).discover("https://publisher.example/site/").map { it.title },
        )
    }

    @Test
    fun aWebsiteWithoutRssCanBeRegisteredAndRefreshedUsingItsSavedList() = withServer { server, address ->
        val site = "https://publisher.example/site/"
        server["/site/"] = {
            Response(
                "<title>Updates</title><main id='news'><article><h2><a href='/posts/4'>Four</a></h2></article>" +
                    "<article><h2><a href='/posts/3'>Three</a></h2></article></main>",
                "text/html; charset=UTF-8",
                etag = "\"v1\"",
            )
        }
        val client = fixtureClient(address)
        val initial = client.discover(site).single()
        val selector = requireNotNull(initial.htmlItemSelector)
        assertEquals(listOf("Four", "Three"), initial.articles.map { it.title })
        server["/site/"] = { headers ->
            assertEquals("\"v1\"", headers["if-none-match"])
            Response(
                "<title>Updates</title><main id='news'><article>" +
                    "<h2><a href='/posts/5'>Five</a></h2><img src='/5.jpg'><p>New article</p></article></main>",
                "text/html; charset=UTF-8",
            )
        }
        val current = requireNotNull(client.refresh(initial.url, initial.etag, initial.modified, selector))
        assertEquals(selector, current.htmlItemSelector)
        assertEquals("https://publisher.example/5.jpg", current.articles.single().thumbnailUrl)
        assertEquals("New article", current.articles.single().body)
    }

    @Test
    fun conditionalRefreshKeepsTheExistingDocumentWhenUnchanged() = withServer { server, address ->
        server["/feed"] = { headers ->
            if (headers["if-none-match"] == "\"version-1\"") {
                Response("", status = "304 Not Modified", etag = "\"version-1\"")
            } else {
                Response(rss("Version 1"), etag = "\"version-1\"")
            }
        }
        val client = fixtureClient(address)
        val feed = requireNotNull(client.refresh("https://publisher.example/feed", null, null))
        assertEquals("\"version-1\"", feed.etag)
        assertNull(client.refresh(feed.url, feed.etag, feed.modified))
    }

    @Test
    fun directHttpFeedIsRejectedBeforeOpeningAConnection() {
        val client = FeedClient { throw AssertionError("An HTTP connection was attempted") }
        assertThrows(FeedRequestException::class.java) {
            client.discover("http://blog.shibu.jp/index20.rdf")
        }
    }

    @Test
    fun httpsFeedCannotRedirectToHttp() = withServer { server, address ->
        server["/feed"] = {
            Response("", status = "302 Found", location = "http://publisher.example/redirected")
        }
        val client = FeedClient { requested ->
            assertEquals("https://publisher.example/feed", requested)
            localConnection(address, requested)
        }
        assertThrows(FeedRequestException::class.java) {
            client.refresh("https://publisher.example/feed", null, null)
        }
    }

    private fun fixtureClient(address: String): FeedClient = FeedClient { requested ->
        assertTrue("An HTTP connection was attempted", requested.startsWith("https://"))
        localConnection(address, requested)
    }

    private fun localConnection(address: String, requested: String): HttpURLConnection =
        URL("$address${URI(requested).rawPath}").openConnection() as HttpURLConnection

    private fun rss(title: String): String = "<rss><channel><title>$title</title></channel></rss>"

    private fun withServer(block: (MutableMap<String, (Map<String, String>) -> Response>, String) -> Unit) {
        val routes = java.util.concurrent.ConcurrentHashMap<String, (Map<String, String>) -> Response>()
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(name = "test-feed-server", isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    socket.use {
                        it.soTimeout = 3_000
                        val incoming = it.getInputStream().bufferedReader()
                        val path = incoming.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        val headers = generateSequence(incoming::readLine).takeWhile(String::isNotEmpty)
                            .associate { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }
                        val response = routes[path]?.invoke(headers) ?: Response("", status = "404 Not Found")
                        val body = response.body.toByteArray()
                        val head = buildString {
                            append("HTTP/1.1 ${response.status}\r\n")
                            append("Content-Type: ${response.type}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
                            response.etag?.let { etag -> append("ETag: $etag\r\n") }
                            response.location?.let { location -> append("Location: $location\r\n") }
                            append("\r\n")
                        }
                        it.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                    }
                }
            }
            try {
                block(routes, "http://127.0.0.1:${server.localPort}")
            } finally {
                server.close()
                worker.join(3_000)
            }
        }
    }

    private data class Response(
        val body: String,
        val type: String = "application/rss+xml; charset=UTF-8",
        val status: String = "200 OK",
        val etag: String? = null,
        val location: String? = null,
    )
}
