package io.github.sasakiy84.qarry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpmlTest {
    @Test
    fun nestedOutlinesPreserveJapaneseTitlesAndDecodeUrlAttributes() {
        val input = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0"><body>
              <outline text="研究">
                <outline text="元の名前" title="学会の新着 &amp; お知らせ" type="rss"
                  xmlUrl="https://example.com/rss?id=123&amp;lang=ja"/>
                <outline text="研究室" type="atom" xmlUrl="https://labs.example.com/atom.xml"/>
              </outline>
              <outline type="rss" xmlUrl="https://untitled.example/feed"/>
            </body></opml>
        """.trimIndent()

        assertEquals(
            listOf(
                OpmlFeed("https://example.com/rss?id=123&lang=ja", "学会の新着 & お知らせ"),
                OpmlFeed("https://labs.example.com/atom.xml", "研究室"),
                OpmlFeed("https://untitled.example/feed", "untitled.example"),
            ),
            readOpml(input.byteInputStream()).feeds,
        )
    }

    @Test
    fun matchesNormalizedDuplicatesAndSkipsUnsupportedUrls() {
        val input = """
            <opml version="2.0"><body>
              <outline text="First" xmlUrl="HTTPS://Example.com:443/feed#latest"/>
              <outline text="Second" xmlUrl="https://example.com/feed"/>
              <outline text="Plain HTTP" xmlUrl="http://example.com/feed"/>
              <outline text="Invalid" xmlUrl="/feed"/>
              <outline text="Web page" htmlUrl="https://example.com/"/>
            </body></opml>
        """.trimIndent()
        assertEquals(
            OpmlContents(listOf(OpmlFeed("https://example.com/feed", "First")), 1, 2),
            readOpml(input.byteInputStream()),
        )
    }

    @Test
    fun anRssFileIsNotAnOpmlSubscriptionList() {
        assertThrows(OpmlFormatException::class.java) {
            readOpml("<rss><channel><title>Feed</title></channel></rss>".byteInputStream())
        }
    }
}
