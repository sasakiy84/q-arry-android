package io.github.sasakiy84.qarry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

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
    notificationSettings: NotificationSettings,
    quoteEnabled: Boolean,
    onQuoteEnabled: (Boolean) -> Unit,
    onQuoteTraining: () -> Unit,
    transfer: LibraryTransferActions,
    archive: ArticleArchive,
    libraryRevision: Int,
    viewed: ArticleListing?,
    rated: RatingListing?,
    onArticle: (Article, ArticlePress) -> Unit,
    onRatedArticle: (RatedArticle, ArticlePress) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    onChangeRating: (RatingKey, ArticleRating?) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    loadingMore: Boolean = false,
    onRetryMore: (() -> Unit)? = null,
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
                DefaultNotificationSettings(notificationSettings)
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                BackgroundUpdateOptions()
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                FeedTransferSettings(transfer)
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                QuoteSettings(quoteEnabled, onQuoteEnabled, onQuoteTraining)
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                ArticleSearchSettings(archive, libraryRevision)
            }
            SettingsTab.Viewed -> when {
                viewed == null -> LoadingIndicator("閲覧履歴を読み込み中")
                viewed.articles.isEmpty() -> EmptyState("閲覧履歴はありません", null)
                else -> {
                    val listState = rememberLazyListState()
                    LoadNextArticlePage(listState, viewed.articles.size, viewed.hasMore && !loadingMore && onRetryMore == null, onMore)
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                        articleCards(viewed.articles, onArticle, onDismiss, history = true)
                        articlePageFooter(loadingMore, onRetryMore)
                    }
                }
            }
            SettingsTab.Rated -> Column {
                TextButton(
                    onClick = transfer.onExportRatings,
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp),
                    enabled = rated != null && transfer.transfer == null && !transfer.choosingFile,
                ) {
                    Text(if (transfer.transfer == LibraryTransfer.ExportingRatings) "保存中" else "エクスポート（JSON）")
                }
                when {
                    rated == null -> LoadingIndicator("評価履歴を読み込み中")
                    rated.articles.isEmpty() -> EmptyState("評価履歴はありません", null)
                    else -> {
                        val listState = rememberLazyListState()
                        LoadNextArticlePage(listState, rated.articles.size, rated.hasMore && !loadingMore && onRetryMore == null, onMore)
                        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(rated.articles, key = { "${it.key.feedUrl}\n${it.key.entryKey}" }) { entry ->
                                RatingHistoryRow(entry, { onRatedArticle(entry, it) }, { onChangeRating(entry.key, it) })
                                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                            }
                            articlePageFooter(loadingMore, onRetryMore)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundUpdateOptions() {
    val updates = (LocalContext.current.applicationContext as QarryApplication).backgroundUpdates
    var settings by remember(updates) { mutableStateOf(updates.settings) }
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(
            "バックグラウンド通信", Modifier.padding(horizontal = 20.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        BackgroundUpdateSwitch("フィードの自動更新は Wi-Fi のみ", settings.feedWifiOnly) { checked ->
            settings = settings.copy(feedWifiOnly = checked)
            updates.update(settings)
        }
        BackgroundUpdateSwitch("自動全文取得は非従量制回線のみ", settings.archiveUnmeteredOnly) { checked ->
            settings = settings.copy(archiveUnmeteredOnly = checked)
            updates.update(settings)
        }
    }
}

@Composable
private fun BackgroundUpdateSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
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
private fun FeedTransferSettings(actions: LibraryTransferActions) {
    val enabled = actions.transfer == null && !actions.choosingFile
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("フィードの移行（OPML）", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(actions.onImport, enabled = enabled) {
                Text(if (actions.transfer == LibraryTransfer.ImportingFeeds) "読込中" else "インポート")
            }
            OutlinedButton(actions.onExport, enabled = enabled) {
                Text(if (actions.transfer == LibraryTransfer.ExportingFeeds) "保存中" else "エクスポート")
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
    notificationSettings: NotificationSettings,
    onNotificationModeChange: (FeedNotificationMode) -> Unit,
    onRefresh: () -> Unit,
    onChangeWebsiteScope: () -> Unit,
    onUnsubscribe: () -> Unit,
    onDismissFeedArticles: () -> Unit,
    onArticle: (Article, ArticlePress) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var unfiltered by rememberSaveable(feed.keywordFilter) { mutableStateOf(false) }
    var showActions by rememberSaveable(feed.id) { mutableStateOf(false) }
    var showNotifications by rememberSaveable(feed.id) { mutableStateOf(false) }
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
    val loadingMore = listing != null && loadedFilter == filter && listing.hasMore && listing.articles.size < limit
    if (failed && !loadingMore) {
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
    LoadNextArticlePage(listState, listing.articles.size + 1, listing.hasMore && !loadingMore) { limit += 75 }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        feed.title, Modifier.weight(1f).padding(end = 4.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    IconToggleButton(checked = feed.isFavorite, onCheckedChange = onFavoriteChange) {
                        Icon(
                            painterResource(if (feed.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_outline),
                            contentDescription = if (feed.isFavorite) "お気に入りを解除" else "お気に入りに登録",
                            tint = if (feed.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showActions = true }) {
                        Icon(painterResource(R.drawable.ic_more), "フィードのその他の操作")
                    }
                }
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    site?.let { address ->
                        OutlinedButton(onClick = { onLink(address) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Text(if (webPage) "監視ページ" else "サイト")
                        }
                    }
                    TextButton(onManage) { Text("キューを設定") }
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        Icon(painterResource(R.drawable.ic_refresh), "今すぐ更新")
                    }
                }
                if (queues.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(queues, key = { it.id }) { queue ->
                            AssistChip(
                                onClick = { onQueue(queue.id) },
                                label = { Text(queue.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.widthIn(max = 220.dp),
                            )
                        }
                    }
                }
                feed.error?.let { error ->
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            error, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).announceStatus(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (webPage && feed.error == WEBSITE_SCOPE_CHANGED) {
                    OutlinedButton(onChangeWebsiteScope, Modifier.padding(horizontal = 16.dp)) { Text("範囲を選び直す") }
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider()
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text("記事一覧", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                TextButton(onDismissFeedArticles, enabled = listing.articles.isNotEmpty() || feed.keywordFilter != null) { Text("一括削除") }
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
        articleCards(listing.articles, onArticle, onDismiss, showFeedTitle = false)
        articlePageFooter(loadingMore, if (failed) ({ attempt++ }) else null)
    }
    if (showActions) {
        val actions = buildList {
            if (site != feed.url) add("フィードURLを開く" to { onLink(feed.url) })
            add((if (feed.keywordFilter != null) "フィルターを編集" else "フィルターを設定") to onKeywordFilter)
            add("新着通知を設定" to { showNotifications = true })
            if (webPage && feed.error != WEBSITE_SCOPE_CHANGED) add("監視範囲を変更" to onChangeWebsiteScope)
            add("購読を解除" to onUnsubscribe)
        }
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text("フィードの操作") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    actions.forEach { (label, action) ->
                        TextButton(
                            onClick = {
                                showActions = false
                                action()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) { Text(label, Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showActions = false }) { Text("閉じる") } },
        )
    }
    if (showNotifications) {
        FeedNotificationDialog(
            feed.notifications, notificationSettings, { showNotifications = false }, onNotificationModeChange,
        )
    }
}
