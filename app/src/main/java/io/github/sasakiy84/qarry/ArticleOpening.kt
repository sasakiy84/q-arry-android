package io.github.sasakiy84.qarry

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.compositionLocalOf
import androidx.core.content.edit
import androidx.core.net.toUri

internal enum class ArticlePress { Tap, LongPress }

internal enum class ArticleOpenMode(val label: String, val actionLabel: String) {
    InApp("アプリ内の記事詳細", "アプリ内で記事を開く"),
    Browser("デフォルトブラウザ", "デフォルトブラウザで記事を開く");

    fun forPress(press: ArticlePress): ArticleOpenMode = when (press) {
        ArticlePress.Tap -> this
        ArticlePress.LongPress -> if (this == InApp) Browser else InApp
    }
}

internal val LocalArticleOpenMode = compositionLocalOf { ArticleOpenMode.InApp }

internal class ArticleOpeningPreference(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: ArticleOpenMode
        get() {
            val saved = preferences.getString("article_open_mode", null)
            return ArticleOpenMode.entries.firstOrNull { it.name == saved } ?: ArticleOpenMode.InApp
        }
        set(value) = preferences.edit { putString("article_open_mode", value.name) }
}

internal fun articleBrowserIntent(address: String): Intent? {
    val page = webUrl(address)?.toUri() ?: return null
    return Intent(Intent.ACTION_VIEW, page).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        // Match browsers against the scheme alone so an App Link cannot take over the article URL.
        selector = Intent(Intent.ACTION_VIEW, "${page.scheme}:".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    }
}

internal fun Context.openArticleInBrowser(address: String): Boolean {
    val intent = articleBrowserIntent(address) ?: return false
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

internal fun articleShareIntent(title: String, address: String): Intent? {
    val url = address.trim().takeIf { webUrl(it) != null } ?: return null
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, if (title == url) url else "$title\n$url")
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_SUBJECT, title)
    }
    return Intent.createChooser(send, null)
}
