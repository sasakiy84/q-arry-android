package io.github.sasakiy84.qarry

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ArticleImageTest {
    @Test
    fun articleImageFallsBackToTheFeedImageThenThePlaceholderIfLoadingFails() {
        val ogUrl = "https://publisher.example/og.jpg"
        val feedUrl = "https://publisher.example/feed.jpg"
        val image = ArticleImage(ogUrl, feedUrl)
        assertEquals(ogUrl, image.url)
        image.markFailed(ogUrl)
        assertEquals(feedUrl, image.url)
        image.markFailed(feedUrl)
        assertNull(image.url)
        assertEquals(feedUrl, ArticleImage(null, feedUrl).url)
        assertEquals(feedUrl, ArticleImage("http://publisher.example/og.jpg", feedUrl).url)
    }

    @Test
    fun readingCachedMetadataDoesNotFetchTheArticleAndDetailsReuseTheResult() = runBlocking {
        val address = "https://publisher.example/article"
        var pageReads = 0
        val client = fixtureWebsiteClient(address) {
            pageReads++
            """<html><head><meta property="og:title" content="Page title"></head></html>"""
        }
        FeedStore(RuntimeEnvironment.getApplication()).use { store ->
            val library = FeedLibrary(store, client)
            assertNull(library.cachedArticleMetadata(address))
            assertEquals(0, pageReads)

            assertEquals("Page title", library.articleMetadata(address)?.title)
            assertEquals("Page title", library.cachedArticleMetadata(address)?.title)
            assertEquals("Page title", library.articleMetadata(address)?.title)
            assertEquals(1, pageReads)
        }
    }
}
