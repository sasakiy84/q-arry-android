package io.github.sasakiy84.qarry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

internal fun LazyListScope.moreArticles(onMore: () -> Unit) {
    item {
        TextButton(onMore, Modifier.padding(start = 12.dp, top = 8.dp)) { Text("さらに表示") }
    }
}

internal enum class SettingsTab(val label: String) {
    General("一般"),
    Viewed("閲覧履歴"),
    Rated("評価履歴"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    tab: SettingsTab,
    onTab: (SettingsTab) -> Unit,
    articleOpenMode: ArticleOpenMode,
    onArticleOpenMode: (ArticleOpenMode) -> Unit,
    transfer: OpmlTransfer?,
    choosingFile: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
    viewed: ArticleListing?,
    rated: RatingListing?,
    onArticle: (Article, ArticlePress) -> Unit,
    onRatedArticle: (RatedArticle, ArticlePress) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    onChangeRating: (RatingKey, ArticleRating?) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PageHeading("設定") { }
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            for (choice in SettingsTab.entries) {
                Tab(selected = tab == choice, onClick = { onTab(choice) }, text = { Text(choice.label) })
            }
        }
        when (tab) {
            SettingsTab.General -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                ArticleOpeningSettings(articleOpenMode, onArticleOpenMode)
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                FeedTransferSettings(transfer, choosingFile, onImport, onExport)
            }
            SettingsTab.Viewed -> when {
                viewed == null -> LoadingIndicator("閲覧履歴を読み込み中")
                viewed.articles.isEmpty() -> EmptyState("閲覧履歴はありません", null)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    articleCards(viewed.articles, onArticle, onDismiss, history = true)
                    if (viewed.hasMore) moreArticles(onMore)
                }
            }
            SettingsTab.Rated -> when {
                rated == null -> LoadingIndicator("評価履歴を読み込み中")
                rated.articles.isEmpty() -> EmptyState("評価履歴はありません", null)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(rated.articles, key = { "${it.key.feedUrl}\n${it.key.entryKey}" }) { entry ->
                        RatingHistoryRow(entry, { onRatedArticle(entry, it) }, { onChangeRating(entry.key, it) })
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    }
                    if (rated.hasMore) moreArticles(onMore)
                }
            }
        }
    }
}

@Composable
private fun ArticleOpeningSettings(mode: ArticleOpenMode, onChange: (ArticleOpenMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        Text(
            "記事カードのタップ",
            Modifier.padding(horizontal = 20.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        Column(Modifier.padding(top = 8.dp).selectableGroup()) {
            for (choice in ArticleOpenMode.entries) {
                val selected = mode == choice
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected, role = Role.RadioButton, onClick = { onChange(choice) })
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun FeedTransferSettings(
    transfer: OpmlTransfer?, choosingFile: Boolean, onImport: () -> Unit, onExport: () -> Unit,
) {
    val enabled = transfer == null && !choosingFile
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("フィードの移行（OPML）", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(onImport, enabled = enabled) {
                Text(if (transfer == OpmlTransfer.Importing) "読込中" else "インポート")
            }
            OutlinedButton(onExport, enabled = enabled) {
                Text(if (transfer == OpmlTransfer.Exporting) "保存中" else "エクスポート")
            }
        }
    }
}

@Composable
private fun RatingHistoryRow(entry: RatedArticle, onOpen: (ArticlePress) -> Unit, onChange: (ArticleRating?) -> Unit) {
    val metadata = rememberArticleMetadata(entry.url)
    val image = rememberArticleImage(metadata?.imageUrl, entry.thumbnailUrl)
    val canOpen = entry.articleId != null || entry.url?.let(::webUrl) != null
    val largeText = LocalDensity.current.fontScale >= 1.5f
    ListItem(
        headlineContent = { Text(metadata?.title ?: entry.title, maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis) },
        overlineContent = { Text(entry.feedTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(entryDate(entry.ratedAt)) },
        trailingContent = {
            ArticleThumbnail(image, if (largeText) Modifier.size(48.dp) else Modifier.size(width = 76.dp, height = 60.dp))
        },
        modifier = if (canOpen) Modifier.articleOpenActions(entry.url, onOpen, entry.articleId != null) else Modifier,
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
    library: FeedLibrary,
    revision: Int,
    refreshing: Boolean,
    onLink: (String) -> Unit,
    onQueue: (Long) -> Unit,
    onManage: () -> Unit,
    onKeywordFilter: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onChangeWebsiteScope: () -> Unit,
    onUnsubscribe: () -> Unit,
    onArticle: (Article, ArticlePress) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var unfiltered by rememberSaveable(feed.keywordFilter) { mutableStateOf(false) }
    var limit by rememberSaveable(feed.keywordFilter) { mutableIntStateOf(75) }
    var articles by remember { mutableStateOf<ArticleListing?>(null) }
    var loadedFilter by remember { mutableStateOf<Pair<KeywordFilter?, Boolean>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val showingUnfiltered = unfiltered && feed.keywordFilter != null
    val filter = feed.keywordFilter to showingUnfiltered
    LaunchedEffect(feed.id, filter, revision, limit, attempt) {
        failed = false
        try {
            articles = library.feedArticles(feed.id, limit, applyKeywordFilter = !showingUnfiltered)
            loadedFilter = filter
        } catch (_: android.database.sqlite.SQLiteException) {
            failed = true
        }
    }
    val listing = articles
    if (failed) {
        EmptyState("フィードの記事を読み込めませんでした", null, { attempt++ }, "再試行")
        return
    }
    if (listing == null || loadedFilter != filter) {
        LoadingIndicator("フィードの記事を読み込み中")
        return
    }
    val webPage = feed.htmlItemSelector != null
    val home = feed.homeUrl?.let(::webUrl)?.takeUnless { it == feed.url }
    val site = if (webPage) feed.url else home ?: webUrl(feed.url)?.let { url ->
        URI(url).let { "${it.scheme}://${it.rawAuthority}/" }
    }

    val listState = key(feed.keywordFilter, showingUnfiltered) { rememberLazyListState() }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        feed.title, Modifier.weight(1f).semantics { heading() },
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
                    TextButton(onKeywordFilter) {
                        Text(feed.keywordFilter?.let { "フィルター（${it.summary}）" } ?: "フィルターを設定")
                    }
                    TextButton(onRefresh, enabled = !refreshing) { Text("今すぐ更新") }
                    if (webPage && feed.error != WEBSITE_SCOPE_CHANGED) {
                        TextButton(onChangeWebsiteScope) { Text("監視範囲を変更") }
                    }
                    TextButton(onUnsubscribe) { Text("購読を解除") }
                }
                feed.error?.let {
                    Text(it, Modifier.announceStatus(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (webPage && feed.error == WEBSITE_SCOPE_CHANGED) {
                    OutlinedButton(onChangeWebsiteScope) { Text("範囲を選び直す") }
                }
            }
            HorizontalDivider()
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text("記事一覧", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                if (feed.keywordFilter != null) {
                    FilterChip(showingUnfiltered, { unfiltered = !unfiltered; limit = 75 }, label = { Text("絞り込み前を表示") })
                }
            }
            if (listing.articles.isEmpty()) {
                val message = when {
                    feed.keywordFilter != null && !showingUnfiltered -> "絞り込み後の記事がありません"
                    feed.error != null -> "取得済みの記事はありません"
                    else -> "表示する記事がありません"
                }
                EmptyState(message, null, scrollable = false)
            }
        }
        articleCards(listing.articles, onArticle, onDismiss)
        if (listing.hasMore) moreArticles { limit += 75 }
    }
}
