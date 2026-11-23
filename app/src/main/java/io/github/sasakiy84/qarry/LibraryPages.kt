package io.github.sasakiy84.qarry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val pagePadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)
private const val UNASSIGNED_FILTER_LABEL = "キュー未設定のみ"

@Composable
internal fun ArticlesPage(
    snapshot: LibrarySnapshot,
    queueId: Long?,
    unread: Boolean,
    markingRead: Boolean,
    onQueueFilter: (Long?, Boolean) -> Unit,
    onUnread: () -> Unit,
    onMarkAllRead: () -> Unit,
    onAdd: () -> Unit,
    onMore: () -> Unit,
    onArticle: (Article, ArticlePress) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    modifier: Modifier = Modifier,
    showQueuePicker: Boolean = true,
    loading: Boolean = false,
    unassignedOnly: Boolean = false,
    onDismissQueueArticles: ((Boolean) -> Unit)? = null,
) {
    val queue = snapshot.queues.firstOrNull { it.id == queueId }
    var confirmMarkAllRead by rememberSaveable { mutableStateOf(false) }
    val listState = key(queueId, unread, unassignedOnly) { rememberLazyListState() }
    Column(modifier) {
        PageHeading(queue?.name ?: "記事") {
            FilterChip(selected = unread, onClick = onUnread, label = { Text("未読のみ") })
            TextButton(
                onClick = { confirmMarkAllRead = true },
                enabled = !loading && !markingRead && snapshot.hasUnread,
            ) { Text("すべて既読にする") }
            if (onDismissQueueArticles != null) {
                TextButton(onClick = { onDismissQueueArticles(unread) }, enabled = !loading && snapshot.articles.isNotEmpty()) {
                    Text("一括削除")
                }
            }
        }
        if (showQueuePicker && (snapshot.subscriptions.isNotEmpty() || snapshot.queues.isNotEmpty())) {
            LazyRow(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = queueId == null && !unassignedOnly,
                        onClick = { onQueueFilter(null, false) },
                        label = { Text("すべて") },
                    )
                }
                item {
                    FilterChip(
                        selected = unassignedOnly,
                        onClick = { onQueueFilter(null, true) },
                        label = { Text(UNASSIGNED_FILTER_LABEL) },
                    )
                }
                items(snapshot.queues, key = { it.id }) { queue ->
                    FilterChip(
                        selected = queue.id == queueId,
                        onClick = { onQueueFilter(queue.id, false) },
                        label = { Text(queue.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
        }
        HorizontalDivider()
        if (loading) {
            LoadingIndicator("記事を読み込み中")
        } else if (snapshot.articles.isEmpty()) {
            val emptyQueue = queue?.feedCount == 0
            val title = when {
                snapshot.subscriptions.isEmpty() -> "最初のフィードを登録"
                emptyQueue -> "このキューにフィードがありません"
                unassignedOnly && snapshot.subscriptions.all { it.queueIds.isNotEmpty() } -> "キュー未設定のフィードがありません"
                unread -> "未読の記事はありません"
                unassignedOnly -> "キュー未設定の記事がありません"
                queueId != null -> "このキューに記事がありません"
                else -> "まだ記事がありません"
            }
            EmptyState(
                title = title,
                description = null,
                action = if (snapshot.subscriptions.isEmpty() || emptyQueue) onAdd else null,
                actionLabel = "フィードを追加",
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                articleCards(snapshot.articles, onArticle, onDismiss)
                if (snapshot.hasMore) moreArticles(onMore)
            }
        }
    }
    if (confirmMarkAllRead) {
        val message = when {
            unassignedOnly -> "キュー未設定のフィードの未読記事をすべて既読にします。"
            queue != null -> "「${queue.name}」の未読記事を既読にします。"
            else -> "この一覧の未読記事をすべて既読にします。"
        }
        AlertDialog(
            onDismissRequest = { confirmMarkAllRead = false },
            title = { Text("すべて既読にしますか？") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    confirmMarkAllRead = false
                    onMarkAllRead()
                }) { Text("既読にする") }
            },
            dismissButton = { TextButton(onClick = { confirmMarkAllRead = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
internal fun QueueArticlesPage(
    library: FeedLibrary,
    queueId: Long,
    revision: Int,
    markingRead: Boolean,
    onMarkAllRead: () -> Unit,
    onDismissQueueArticles: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onArticle: (Article, ArticlePress) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var unread by rememberSaveable { mutableStateOf(true) }
    var limit by rememberSaveable { mutableIntStateOf(75) }
    var snapshot by remember { mutableStateOf<LibrarySnapshot?>(null) }
    var loadedUnread by remember { mutableStateOf<Boolean?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(queueId, unread, limit, revision, attempt) {
        failed = false
        val requestedUnread = unread
        try {
            snapshot = library.load(queueId, requestedUnread, limit)
            loadedUnread = requestedUnread
        } catch (_: android.database.sqlite.SQLiteException) {
            failed = true
        }
    }
    val content = snapshot
    when {
        failed -> EmptyState("記事を読み込めませんでした", null, { attempt++ }, "再試行")
        content == null -> LoadingIndicator("キューの記事を読み込み中")
        else -> ArticlesPage(
            content, queueId, unread, markingRead,
            onQueueFilter = { _, _ -> }, onUnread = { unread = !unread; limit = 75 }, onMarkAllRead = onMarkAllRead,
            onAdd = onAdd, onMore = { limit += 75 }, onArticle = onArticle, onDismiss = onDismiss,
            modifier = modifier, showQueuePicker = false, loading = unread != loadedUnread,
            onDismissQueueArticles = onDismissQueueArticles,
        )
    }
}

@Composable
internal fun ArticlePage(
    article: Article,
    onFeed: () -> Unit,
    onRemove: () -> Unit,
    onRate: (ArticleRating) -> Unit,
    onReadChange: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    onFetchFullText: (() -> Unit)? = null,
    fetchingFullText: Boolean = false,
    quoteLearning: QuoteLearning? = null,
) {
    val metadata = rememberArticleMetadata(article.url)
    val image = rememberArticleImage(metadata?.imageUrl, article.thumbnailUrl)
    val title = metadata?.title ?: article.displayTitle
    val articleUrl = article.url?.takeIf { webUrl(it) != null }
    val canRate = !article.dismissed || (article.originalOpenedAt != null && article.rating == null)
    val paragraphs = remember(article.archivedBody) {
        article.archivedBody?.split("\n\n")?.flatMap { it.chunked(3500) }
    }
    LazyColumn(modifier, contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            TextButton(onFeed, contentPadding = PaddingValues(0.dp)) {
                Text(article.feedTitle, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            article.timestampLabel(metadata)?.let { date ->
                Spacer(Modifier.height(8.dp))
                Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val body = article.archivedBody
            if (quoteLearning != null && body != null) {
                ArticleLeadQuote(
                    quoteLearning, QuoteSource(article.id, title, body),
                    Modifier.padding(top = 12.dp),
                )
            }
            articleUrl?.let { url ->
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpen(url) }) { Text("元記事") }
                    OutlinedButton(onClick = { onShare(title, url) }) { Text("共有") }
                }
            }
        }
        item {
            ArticleThumbnail(image, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
        if (paragraphs != null) {
            items(paragraphs) { paragraph ->
                SelectionContainer { Text(paragraph, style = MaterialTheme.typography.bodyLarge) }
            }
        } else {
            item {
                Column {
                    SelectionContainer {
                        Text(
                            metadata?.description ?: article.body ?: "説明文はありません。",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (onFetchFullText != null) {
                        TextButton(onClick = onFetchFullText, enabled = !fetchingFullText) {
                            Text(if (fetchingFullText) "全文を取得中" else "全文を取得")
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                if (article.dismissed && canRate) {
                    Text(
                        "元記事を開いた・キューから削除済み", Modifier.announceStatus(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!canRate) {
                        Text(
                            article.rating?.let { "${it.label} ・ キューから削除済み" } ?: "キューから削除済み",
                            Modifier.announceStatus(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ArticleRating.entries.forEach { rating ->
                                OutlinedButton(
                                    onClick = { onRate(rating) },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                ) { Text(rating.label) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onReadChange(!article.read) }) {
                            Text(if (article.read) "未読に戻す" else "既読にする")
                        }
                        if (!article.dismissed) {
                            RowMenu("その他の操作", listOf("すべてのキューから削除" to onRemove))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QueuesPage(
    queues: List<FeedQueue>,
    onOpen: (FeedQueue) -> Unit,
    onAdd: () -> Unit,
    onRename: (FeedQueue) -> Unit,
    onDelete: (FeedQueue) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PageHeading("キュー") { OutlinedButton(onAdd) { Text("新規作成") } }
        HorizontalDivider()
        if (queues.isEmpty()) {
            EmptyState("キューがありません", null)
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(queues, key = { it.id }) { queue ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "キューの記事を開く") { onOpen(queue) }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(queue.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${queue.feedCount} 件のフィード",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RowMenu("「${queue.name}」の操作", listOf("名前を変更" to { onRename(queue) }, "削除" to { onDelete(queue) }))
                        Spacer(Modifier.width(12.dp))
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

@Composable
internal fun FeedsPage(
    snapshot: LibrarySnapshot,
    refreshing: Boolean,
    unassignedOnly: Boolean,
    onUnassignedOnly: () -> Unit,
    favoritesOnly: Boolean,
    onFavoritesOnly: () -> Unit,
    order: FeedOrder,
    onOrder: (FeedOrder) -> Unit,
    onAdd: () -> Unit,
    onOpen: (Subscription) -> Unit,
    onManage: (Subscription) -> Unit,
    onRefresh: (Subscription) -> Unit,
    onDelete: (Subscription) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PageHeading("フィード") {
            Button(onAdd) { Text("追加") }
        }
        if (snapshot.subscriptions.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = favoritesOnly, onClick = onFavoritesOnly, label = { Text("お気に入りのみ") })
                FilterChip(selected = unassignedOnly, onClick = onUnassignedOnly, label = { Text(UNASSIGNED_FILTER_LABEL) })
            }
            Box(Modifier.align(Alignment.End).padding(end = 12.dp)) {
                FeedOrderMenu(order, onOrder)
            }
        }
        HorizontalDivider()
        val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
        val feeds = remember(snapshot.subscriptions, order, unassignedOnly, favoritesOnly, locale) {
            order.sort(snapshot.subscriptions.filter {
                (!unassignedOnly || it.queueIds.isEmpty()) && (!favoritesOnly || it.isFavorite)
            }, locale)
        }
        if (snapshot.subscriptions.isEmpty()) {
            EmptyState("フィードがありません", null)
        } else if (feeds.isEmpty()) {
            EmptyState("条件に合うフィードはありません", null)
        } else {
            val names = snapshot.queues.associate { it.id to it.name }
            val listState = key(order, unassignedOnly, favoritesOnly) { rememberLazyListState() }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(feeds, key = { it.id }) { feed ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "フィードの詳細を開く") { onOpen(feed) }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (feed.isFavorite) {
                                    Icon(
                                        painterResource(R.drawable.ic_favorite), "お気に入り",
                                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(feed.title, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                hostLabel(feed.url),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                feed.queueIds.mapNotNull(names::get).joinToString("・").ifEmpty { "キュー未設定" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            feed.error?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        val actions = buildList {
                            add("キューを設定" to { onManage(feed) })
                            if (!refreshing) add("今すぐ更新" to { onRefresh(feed) })
                            add("購読を解除" to { onDelete(feed) })
                        }
                        RowMenu("「${feed.title}」の操作", actions)
                        Spacer(Modifier.width(12.dp))
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun FeedOrderMenu(order: FeedOrder, onOrder: (FeedOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text("並び替え　${order.label}")
    }
    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
        for (choice in FeedOrder.entries) {
            DropdownMenuItem(
                text = { Text(choice.label) },
                onClick = {
                    onOrder(choice)
                    expanded = false
                },
                modifier = Modifier.semantics { selected = choice == order },
                trailingIcon = if (choice == order) { { Text("✓", Modifier.semantics { hideFromAccessibility() }) } } else null,
            )
        }
    }
}

@Composable
internal fun PageHeading(title: String, action: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 7.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            Modifier.align(Alignment.CenterVertically).semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Serif,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.align(Alignment.CenterVertically),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            action()
        }
    }
}

@Composable
internal fun EmptyState(
    title: String,
    description: String?,
    action: (() -> Unit)? = null,
    actionLabel: String = "",
    scrollable: Boolean = true,
) {
    Column(
        (if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (action != null) Button(action) { Text(actionLabel) }
    }
}

@Composable
private fun RowMenu(description: String, actions: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more), description)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() })
            }
        }
    }
}

private val entryDateFormat = DateTimeFormatter.ofPattern("yyyy/M/d")
private val articleTimestampFormat = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")

internal fun entryDate(epoch: Long): String =
    entryDateFormat.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))

internal fun Article.timestampLabel(metadata: ArticleMetadata?): String? = articleTimestampLabel(
    metadata?.updatedAt ?: updatedAt, metadata?.publishedTime ?: publishedTime, changeDetectedAt, discoveredAt,
)

internal fun articleTimestampLabel(
    updatedAt: Long?,
    publishedTime: Long?,
    changeDetectedAt: Long? = null,
    discoveredAt: Long?,
): String? {
    val time = updatedAt ?: changeDetectedAt ?: publishedTime ?: discoveredAt ?: return null
    return articleTimestampFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
}

private fun hostLabel(url: String): String = java.net.URI(url).host ?: url
