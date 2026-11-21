package io.github.sasakiy84.qarry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.net.URI

@Composable
internal fun SwipeHint() {
    Text(
        "右にスワイプで Good、左で Bad",
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

internal fun LazyListScope.moreArticles(onMore: () -> Unit) {
    item {
        TextButton(onMore, Modifier.padding(start = 12.dp, top = 8.dp)) { Text("さらに表示") }
    }
}

@Composable
internal fun HistoryPage(
    listing: ArticleListing,
    onArticle: (Article) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PageHeading("閲覧履歴") { }
        HorizontalDivider()
        if (listing.articles.isEmpty()) {
            EmptyState("閲覧履歴はありません", "記事を開くと、ここに表示されます。")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                articleCards(listing.articles, onArticle, onDismiss, history = true)
                if (listing.hasMore) moreArticles(onMore)
            }
        }
    }
}

@Composable
internal fun FeedDetailPage(
    feed: Subscription,
    queues: List<FeedQueue>,
    listing: ArticleListing,
    refreshing: Boolean,
    onLink: (String) -> Unit,
    onQueue: (Long) -> Unit,
    onManage: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onUnsubscribe: () -> Unit,
    onArticle: (Article) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val webPage = feed.htmlItemSelector != null
    val home = feed.homeUrl?.let(::webUrl)?.takeUnless { it == feed.url }
    val site = if (webPage) feed.url else home ?: webUrl(feed.url)?.let { url ->
        URI(url).let { "${it.scheme}://${it.rawAuthority}/" }
    }

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        feed.title, Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    )
                    IconToggleButton(checked = feed.isFavorite, onCheckedChange = onFavoriteChange) {
                        Icon(
                            painterResource(if (feed.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_outline),
                            contentDescription = if (feed.isFavorite) "お気に入りを解除" else "お気に入りに登録",
                            tint = if (feed.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    site?.let { address ->
                        OutlinedButton(onClick = { onLink(address) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Text(
                                when {
                                    webPage -> "登録ページを開く"
                                    home != null -> "トップページを開く"
                                    else -> "配信元サイトを開く"
                                },
                            )
                        }
                    }
                    if (site != feed.url) {
                        OutlinedButton(onClick = { onLink(feed.url) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Text("フィードURLを開く")
                        }
                    }
                }
                if (queues.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        queues.forEach { queue -> AssistChip(onClick = { onQueue(queue.id) }, label = { Text(queue.name) }) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onManage) { Text("キューを設定") }
                    TextButton(onRefresh, enabled = !refreshing) { Text("今すぐ更新") }
                    TextButton(onUnsubscribe) { Text("購読を解除") }
                }
                feed.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            if (listing.articles.isEmpty()) {
                EmptyState("表示する記事がありません", null)
            } else {
                Text(
                    "記事一覧", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        articleCards(listing.articles, onArticle, onDismiss)
        if (listing.hasMore) moreArticles(onMore)
    }
}
