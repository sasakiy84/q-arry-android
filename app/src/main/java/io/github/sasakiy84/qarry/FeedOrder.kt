package io.github.sasakiy84.qarry

import java.text.Collator
import java.util.Locale

internal enum class FeedOrder(val label: String) {
    Name("名前順"),
    Updated("更新順"),
    Registered("登録順");

    fun sort(feeds: List<Subscription>, locale: Locale): List<Subscription> = when (this) {
        Name -> feeds.sortedWith(compareBy(Collator.getInstance(locale), Subscription::title).thenBy { it.id })
        Updated -> feeds.sortedWith(compareByDescending(Subscription::latestArticleAt).thenByDescending { it.id })
        Registered -> feeds.sortedByDescending { it.id }
    }
}
