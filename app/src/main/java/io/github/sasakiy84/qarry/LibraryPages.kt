package io.github.sasakiy84.qarry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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

private val pagePadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)

@Composable
fun ArticlesPage(
    snapshot: LibrarySnapshot,
    queueId: Long?,
    unread: Boolean,
    onQueue: (Long?) -> Unit,
    onUnread: () -> Unit,
    onAdd: () -> Unit,
    onMore: () -> Unit,
    onArticle: (Article) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PageHeading(snapshot.queues.firstOrNull { it.id == queueId }?.name ?: "記事") {
            FilterChip(selected = unread, onClick = onUnread, label = { Text("未読のみ") })
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
                description = when {
                    snapshot.subscriptions.isEmpty() -> "サイトかフィードのURLを入力すると、記事をここで読めます。"
                    queueId != null && !unread -> "フィード画面から、このキューにフィードを追加できます。"
                    else -> null
                },
                action = if (snapshot.subscriptions.isEmpty()) onAdd else null,
                actionLabel = "フィードを追加",
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(snapshot.articles, key = { it.id }) { article ->
                    ArticleRow(article, onClick = { onArticle(article) })
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }
                if (snapshot.hasMore) item {
                    TextButton(onMore, Modifier.padding(start = 12.dp, top = 8.dp)) { Text("さらに表示") }
                }
            }
        }
    }
}

@Composable
private fun ArticleRow(article: Article, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                article.feedTitle,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!article.read) Text("未読", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            article.published?.let {
                Text(entryDate(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    article.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (article.read) FontWeight.Medium else FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                article.body?.let { description ->
                    Text(
                        description.take(240).trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ArticleThumbnail(
                article.url,
                article.thumbnailUrl,
                Modifier.size(width = 84.dp, height = 64.dp),
            )
        }
    }
}

@Composable
fun ArticlePage(article: Article, onReadChange: (Boolean) -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text(article.feedTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(article.displayTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            article.published?.let {
                Spacer(Modifier.height(8.dp))
                Text(entryDate(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            ArticleThumbnail(article.url, article.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                article.url?.let { url -> Button(onClick = { onOpen(url) }) { Text("元の記事を開く") } }
                article.thumbnailUrl?.let { url ->
                    TextButton(onClick = { onOpen(url) }) { Text("サムネイル画像を開く") }
                }
                TextButton(onClick = { onReadChange(!article.read) }) {
                    Text(if (article.read) "未読に戻す" else "既読にする")
                }
            }
            HorizontalDivider()
        }
        item {
            SelectionContainer {
                Text(
                    article.body ?: "説明文はありません。",
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
            EmptyState("キューがありません", "フィードをキューに登録すると、キューごとに記事を読めます。")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(queues, key = { it.id }) { queue ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).clickable(role = Role.Button) { onOpen(queue) }
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
                        RowMenu("キューの操作", listOf("名前を変更" to { onRename(queue) }, "削除" to { onDelete(queue) }))
                        Spacer(Modifier.width(12.dp))
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

@Composable
fun FeedsPage(
    snapshot: LibrarySnapshot,
    refreshing: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    onAdd: () -> Unit,
    onManage: (Subscription) -> Unit,
    onRefresh: (Subscription) -> Unit,
    onDelete: (Subscription) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PageHeading("フィード") {
            OutlinedButton(onImport, enabled = !importing, contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text(if (importing) "読込中" else "インポート")
            }
            Button(onAdd) { Text("追加") }
        }
        HorizontalDivider()
        if (snapshot.subscriptions.isEmpty()) {
            EmptyState("フィードがありません", "URLを入力するか、OPMLをインポートして追加できます。")
        } else {
            val names = snapshot.queues.associate { it.id to it.name }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(snapshot.subscriptions, key = { it.id }) { feed ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).clickable(role = Role.Button) { onManage(feed) }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(feed.title, style = MaterialTheme.typography.titleMedium)
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
                        RowMenu("フィードの操作", actions)
                        Spacer(Modifier.width(12.dp))
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }
            }
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
    var found by remember { mutableStateOf<List<FeedDocument>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun search() {
        if (url.isBlank() || busy) return
        focus.clearFocus()
        busy = true
        error = null
        found = emptyList()
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

    LazyColumn(
        modifier, contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null; found = emptyList() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text("サイトまたはフィードのURL") },
                placeholder = { Text("https://example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
        }
        item { Button(onClick = ::search, enabled = url.isNotBlank() && !busy) { Text("フィードを探す") } }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        items(found, key = { it.url }) { feed ->
            val existing = feed.url in subscribedUrls
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(feed.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        feed.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${feed.articles.size} 件の記事", style = MaterialTheme.typography.labelMedium)
                    if (feed.htmlItemSelector != null) WebsiteArticlePreview(feed.articles)
                    Button(
                        enabled = !busy && (!existing || queueId != null),
                        onClick = {
                            if (!busy) {
                                busy = true
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
                        },
                    ) { Text(if (existing && queueId != null) "このキューに追加" else if (existing) "登録済み" else "登録") }
                }
            }
        }
    }
}

@Composable
private fun WebsiteArticlePreview(articles: List<FeedArticle>) {
    Text("Webページから抽出", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    for (article in articles.take(3)) {
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            article.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            article.url?.let { PreviewMetadata(it) }
            article.body?.let { PreviewMetadata(it) }
            article.thumbnailUrl?.let { PreviewMetadata("画像 $it") }
        }
    }
}

@Composable
private fun PreviewMetadata(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
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
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(::save, enabled = name.isNotBlank() && !saving) { Text("保存") } },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("キャンセル") } },
    )
}

@Composable
fun QueueMembershipDialog(
    feed: Subscription,
    queues: List<FeedQueue>,
    onDismiss: () -> Unit,
    onCreateQueue: () -> Unit,
    onSave: (Set<Long>) -> Unit,
) {
    var selected by remember(feed.id) { mutableStateOf(feed.queueIds) }
    var saving by remember(feed.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("キューを設定") },
        text = {
            Column {
                Text(feed.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (queues.isEmpty()) {
                    Text("キューを作成すると、このフィードを登録できます。")
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(queues, key = { it.id }) { queue ->
                            val checked = queue.id in selected
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .toggleable(checked, role = Role.Checkbox) { value ->
                                        selected = if (value) selected + queue.id else selected - queue.id
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Checkbox(checked, onCheckedChange = null)
                                Text(queue.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (queues.isEmpty()) {
                TextButton(onCreateQueue) { Text("キューを作成") }
            } else {
                TextButton(onClick = { saving = true; onSave(selected) }, enabled = !saving) { Text("保存") }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun PageHeading(title: String, action: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 7.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            Modifier.align(Alignment.CenterVertically),
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
private fun EmptyState(
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

private fun entryDate(epoch: Long): String =
    entryDateFormat.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))

private fun hostLabel(url: String): String = java.net.URI(url).host ?: url
