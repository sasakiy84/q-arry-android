package io.github.sasakiy84.qarry

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
