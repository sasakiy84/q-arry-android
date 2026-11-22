package io.github.sasakiy84.qarry

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ArticleOpeningTest {
    @Test
    fun persistedOpenModeUsesTheOtherDestinationForLongPress() {
        val context = RuntimeEnvironment.getApplication()
        val first = ArticleOpeningPreference(context)
        assertEquals(ArticleOpenMode.InApp, first.mode.forPress(ArticlePress.Tap))
        assertEquals(ArticleOpenMode.Browser, first.mode.forPress(ArticlePress.LongPress))

        first.mode = ArticleOpenMode.Browser
        val reopened = ArticleOpeningPreference(context)
        assertEquals(ArticleOpenMode.Browser, reopened.mode.forPress(ArticlePress.Tap))
        assertEquals(ArticleOpenMode.InApp, reopened.mode.forPress(ArticlePress.LongPress))
    }

    @Test
    fun browserIntentPreservesTheArticleUrlAndMatchesOnlyGeneralWebHandlers() {
        val url = "https://news.example/article?issue=3"
        val intent = checkNotNull(articleBrowserIntent(url))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        val selector = checkNotNull(intent.selector)
        val browser = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
        }
        val publisherApp = IntentFilter(browser).apply { addDataAuthority("news.example", null) }
        assertTrue(browser.match(selector.action, null, selector.scheme, selector.data, selector.categories, "test") >= 0)
        assertEquals(
            IntentFilter.NO_MATCH_DATA,
            publisherApp.match(selector.action, null, selector.scheme, selector.data, selector.categories, "test"),
        )
        assertNull(articleBrowserIntent("intent://news.example/article"))
    }

    @Test
    fun sharingIncludesTheDisplayedTitleAndOriginalUrlInTheText() {
        val title = "新着資料・研究のお知らせ"
        val url = "https://news.example/article?issue=3&lang=ja#references"
        val chooser = checkNotNull(articleShareIntent(title, url))
        val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("$title\n$url", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(title, send.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(title, send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(articleShareIntent(title, "intent://news.example/article"))
    }
}
