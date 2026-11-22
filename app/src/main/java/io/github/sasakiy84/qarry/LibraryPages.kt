package io.github.sasakiy84.qarry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val pagePadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)

@Composable
internal fun ArticlesPage(
    snapshot: LibrarySnapshot,
    queueId: Long?,
    unread: Boolean,
    markingRead: Boolean,
    onQueue: (Long?) -> Unit,
    onUnread: () -> Unit,
    onMarkAllRead: () -> Unit,
    onAdd: () -> Unit,
    onMore: () -> Unit,
    onArticle: (Article, ArticlePress) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val queue = snapshot.queues.firstOrNull { it.id == queueId }
    var confirmMarkAllRead by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        PageHeading(queue?.name ?: "記事") {
            FilterChip(selected = unread, onClick = onUnread, label = { Text("未読のみ") })
            TextButton(
                onClick = { confirmMarkAllRead = true },
                enabled = !markingRead && (snapshot.hasMore || snapshot.articles.any { !it.read }),
            ) { Text("すべて既読にする") }
        }
        if (snapshot.queues.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(selected = queueId == null, onClick = { onQueue(null) }, label = { Text("すべて") })
                }
                items(snapshot.queues, key = { it.id }) { queue ->
                    FilterChip(
                        selected = queue.id == queueId,
                        onClick = { onQueue(queue.id) },
                        label = { Text(queue.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
        }
        HorizontalDivider()
        if (snapshot.articles.isEmpty()) {
            val title = when {
                snapshot.subscriptions.isEmpty() -> "最初のフィードを登録"
                unread -> "未読の記事はありません"
                queueId != null -> "このキューに記事がありません"
                else -> "まだ記事がありません"
            }
            EmptyState(
                title = title,
                description = if (queueId != null && !unread && snapshot.subscriptions.isNotEmpty()) {
                    "フィード画面から、このキューにフィードを追加できます。"
                } else null,
                action = if (snapshot.subscriptions.isEmpty()) onAdd else null,
                actionLabel = "フィードを追加",
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                articleCards(snapshot.articles, onArticle, onDismiss)
                if (snapshot.hasMore) moreArticles(onMore)
            }
        }
    }
    if (confirmMarkAllRead) {
        AlertDialog(
            onDismissRequest = { confirmMarkAllRead = false },
            title = { Text("すべて既読にしますか？") },
            text = { Text(queue?.let { "「${it.name}」の未読記事を既読にします。" } ?: "この一覧の未読記事をすべて既読にします。") },
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
fun ArticlePage(
    article: Article,
    onFeed: () -> Unit,
    onRemove: () -> Unit,
    onRate: (ArticleRating) -> Unit,
    onReadChange: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metadata = rememberArticleMetadata(article.url)
    val image = rememberArticleImage(metadata?.imageUrl, article.thumbnailUrl)
    val title = metadata?.title ?: article.displayTitle
    val articleUrl = article.url?.takeIf { webUrl(it) != null }
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
            article.published?.let {
                Spacer(Modifier.height(8.dp))
                Text(entryDate(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            ArticleThumbnail(image, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                articleUrl?.let { url ->
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Button(onClick = { onOpen(url) }) { Text("元の記事を開く") }
                        OutlinedButton(onClick = { onShare(title, url) }) { Text("共有") }
                    }
                }
                TextButton(onClick = { onReadChange(!article.read) }) {
                    Text(if (article.read) "未読に戻す" else "既読にする")
                }
                if (article.dismissed) {
                    Text(
                        article.rating?.let { "${it.label} ・ キューから削除済み" } ?: "キューから削除済み",
                        Modifier.announceStatus(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ArticleRating.entries.forEach { rating ->
                            OutlinedButton(onClick = { onRate(rating) }) { Text("${rating.label} と評価") }
                        }
                    }
                    TextButton(onRemove) { Text("すべてのキューから削除") }
                }
            }
            HorizontalDivider()
        }
        item {
            SelectionContainer {
                Text(
                    metadata?.description ?: article.body ?: "説明文はありません。",
                    style = MaterialTheme.typography.bodyLarge,
                )
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
                FilterChip(selected = unassignedOnly, onClick = onUnassignedOnly, label = { Text("キュー未設定のみ") })
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
            val listState = key(order) { rememberLazyListState() }
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
fun AddFeedPage(
    library: FeedLibrary,
    queueId: Long?,
    subscribedUrls: Set<String>,
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf(FeedDiscovery()) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun search() {
        if (url.isBlank() || busy) return
        focus.clearFocus()
        busy = true
        error = null
        found = FeedDiscovery()
        scope.launch {
            try {
                found = library.search(url)
            } catch (failure: FeedRequestException) {
                error = failure.message
            } finally {
                busy = false
            }
        }
    }

    fun save(feed: FeedDocument) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                library.subscribe(feed, queueId)
                onAdded()
            } catch (_: android.database.sqlite.SQLiteException) {
                error = "フィードを端末に保存できませんでした。"
            } finally {
                busy = false
            }
        }
    }

    found.website?.let { website ->
        WebsiteScopePage(
            website = website,
            busy = busy,
            alreadySubscribed = website.options.first().feed.url in subscribedUrls,
            error = error,
            onChangeUrl = { found = FeedDiscovery(); error = null },
            onRegister = ::save,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier, contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null; found = FeedDiscovery() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text("WebページまたはRSSのURL") },
                placeholder = { Text("https://example.com") },
                supportingText = { Text("RSSが見つからない場合は、Webページ内の一覧を探します。") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
        }
        item { Button(onClick = ::search, enabled = url.isNotBlank() && !busy) { Text("登録候補を確認") } }
        if (busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "フィードを処理中" })
        }
        error?.let { message -> item { Text(message, Modifier.announceStatus(), color = MaterialTheme.colorScheme.error) } }
        items(found.feeds, key = { it.url }) { feed ->
            val existing = feed.url in subscribedUrls
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "RSS・Atomフィード",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(feed.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        feed.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${feed.articles.size}件を取得", style = MaterialTheme.typography.labelMedium)
                    Button(
                        enabled = !busy && (!existing || queueId != null),
                        onClick = { save(feed) },
                    ) {
                        Text(
                            when {
                                existing && queueId != null -> "このキューに追加"
                                existing -> "登録済み"
                                else -> "フィードを登録"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QueueNameDialog(editor: QueueEditor, onDismiss: () -> Unit, onSave: suspend (String) -> String?) {
    var name by rememberSaveable { mutableStateOf(editor.name) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save() {
        if (name.isBlank() || saving) return
        saving = true
        failure = null
        scope.launch {
            try {
                failure = onSave(name.trim())
            } finally {
                saving = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (editor.id == null) "キューを作成" else "キュー名を変更") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 80) { name = it; failure = null } },
                    label = { Text("キュー名") },
                    enabled = !saving,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                failure?.let { Text(it, Modifier.announceStatus(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(::save, enabled = name.isNotBlank() && !saving) { Text("保存") } },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("キャンセル") } },
    )
}

@Composable
internal fun QueueMembershipDialog(
    feed: Subscription,
    queues: List<FeedQueue>,
    onDismiss: () -> Unit,
    onCreateQueue: () -> Unit,
    onSave: suspend (Set<Long>, Map<Long, KeywordFilter>) -> String?,
) {
    var selected by remember(feed.id) { mutableStateOf(feed.queueIds) }
    var filters by remember(feed.id) { mutableStateOf(feed.queueKeywordFilters) }
    var editingQueue by remember(feed.id) { mutableStateOf<FeedQueue?>(null) }
    var saving by remember(feed.id) { mutableStateOf(false) }
    var failure by remember(feed.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val selectedFilters = filters.filterKeys { it in selected }

    editingQueue?.let { queue ->
        KeywordFilterDialog(
            scopeLabel = queue.name,
            initial = filters[queue.id],
            replaceMessage = if (feed.keywordFilter != null) "キュー設定を保存すると、フィード全体のフィルターを解除します。" else null,
            onDismiss = { editingQueue = null },
            onSave = { rule ->
                filters = if (rule == null) filters - queue.id else filters + (queue.id to rule)
                editingQueue = null
                null
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("キューを設定") },
        text = {
            Column {
                Text(feed.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (queues.isEmpty()) {
                    Text("キューがありません。")
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(queues, key = { it.id }) { queue ->
                            val checked = queue.id in selected
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(
                                    Modifier.weight(1f).heightIn(min = 48.dp)
                                        .toggleable(checked, enabled = !saving, role = Role.Checkbox) { value ->
                                            selected = if (value) selected + queue.id else selected - queue.id
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Checkbox(checked, onCheckedChange = null, enabled = !saving)
                                    Text(queue.name)
                                }
                                if (checked) {
                                    TextButton(onClick = { editingQueue = queue }, enabled = !saving) {
                                        Text(filters[queue.id]?.summary ?: "フィルター")
                                    }
                                }
                            }
                        }
                    }
                    if (feed.keywordFilter != null && selectedFilters.isNotEmpty()) {
                        Text(
                            "保存すると、フィード全体のフィルターを解除します。",
                            Modifier.announceStatus(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                failure?.let { Text(it, Modifier.announceStatus(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (queues.isEmpty()) {
                TextButton(onCreateQueue) { Text("キューを作成") }
            } else {
                TextButton(onClick = {
                    if (saving) return@TextButton
                    saving = true
                    scope.launch {
                        try {
                            failure = onSave(selected, selectedFilters)
                        } finally {
                            saving = false
                        }
                    }
                }, enabled = !saving) { Text("保存") }
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("キャンセル") } },
    )
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
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
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

internal fun entryDate(epoch: Long): String =
    entryDateFormat.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))

private fun hostLabel(url: String): String = java.net.URI(url).host ?: url
