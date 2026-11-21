package io.github.sasakiy84.qarry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

@Composable
internal fun SwipeHint() {
    Text(
        "右に引いて離すと Good、左は Bad",
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

internal enum class HistoryTab(val label: String) {
    Viewed("閲覧履歴"),
    Rated("評価履歴"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryPage(
    tab: HistoryTab,
    onTab: (HistoryTab) -> Unit,
    viewed: ArticleListing?,
    rated: RatingListing?,
    onArticle: (Article) -> Unit,
    onRatedArticle: (RatedArticle) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    onChangeRating: (RatingKey, ArticleRating?) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PageHeading("履歴") { }
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            for (choice in HistoryTab.entries) {
                Tab(selected = tab == choice, onClick = { onTab(choice) }, text = { Text(choice.label) })
            }
        }
        HorizontalDivider()
        when (tab) {
            HistoryTab.Viewed -> when {
                viewed == null -> CircularProgressIndicator(Modifier.padding(28.dp))
                viewed.articles.isEmpty() -> EmptyState("閲覧履歴はありません", "記事を開くと、ここに表示されます。")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    articleCards(viewed.articles, onArticle, onDismiss, history = true)
                    if (viewed.hasMore) moreArticles(onMore)
                }
            }
            HistoryTab.Rated -> when {
                rated == null -> CircularProgressIndicator(Modifier.padding(28.dp))
                rated.articles.isEmpty() -> EmptyState("評価履歴はありません", "Good や Bad を記録した記事がここに表示されます。")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(rated.articles, key = { "${it.key.feedUrl}\n${it.key.entryKey}" }) { entry ->
                        RatingHistoryRow(entry, { onRatedArticle(entry) }, { onChangeRating(entry.key, it) })
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    }
                    if (rated.hasMore) moreArticles(onMore)
                }
            }
        }
    }
}

@Composable
private fun RatingHistoryRow(entry: RatedArticle, onOpen: () -> Unit, onChange: (ArticleRating?) -> Unit) {
    val canOpen = entry.articleId != null || entry.url?.let(::webUrl) != null
    ListItem(
        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        overlineContent = { Text(entry.feedTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(entryDate(entry.ratedAt)) },
        trailingContent = {
            ArticleThumbnail(entry.url, entry.thumbnailUrl, Modifier.size(width = 76.dp, height = 60.dp))
        },
        modifier = if (canOpen) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier,
    )
    FlowRow(
        Modifier.padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        SingleChoiceSegmentedButtonRow {
            for (rating in ArticleRating.entries) {
                SegmentedButton(
                    selected = entry.rating == rating,
                    onClick = { if (entry.rating != rating) onChange(rating) },
                    shape = SegmentedButtonDefaults.itemShape(rating.ordinal, ArticleRating.entries.size),
                    label = { Text(rating.label) },
                )
            }
        }
        TextButton(onClick = { onChange(null) }) { Text("評価を取り消す") }
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
