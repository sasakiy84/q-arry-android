package io.github.sasakiy84.qarry

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
            MaterialTheme(colorScheme = colors) {
                QarryScreen(feedLibrary())
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2851C7), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EAFF), onPrimaryContainer = Color(0xFF153477),
    background = Color(0xFFFBFAF6), onBackground = Color(0xFF1C2333),
    surface = Color(0xFFFBFAF6), onSurface = Color(0xFF1C2333),
    surfaceVariant = Color(0xFFE9EBF2), onSurfaceVariant = Color(0xFF49546A),
    surfaceContainer = Color(0xFFF1F0EC), surfaceContainerHigh = Color(0xFFEDECE9),
    outline = Color(0xFF747E92), outlineVariant = Color(0xFFD8DAE2),
    tertiary = Color(0xFF8C5200), tertiaryContainer = Color(0xFFFFE5BD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF102A68),
    primaryContainer = Color(0xFF243D78), onPrimaryContainer = Color(0xFFE3EAFF),
    background = Color(0xFF11141C), onBackground = Color(0xFFE4E8F4),
    surface = Color(0xFF11141C), onSurface = Color(0xFFE4E8F4),
    surfaceVariant = Color(0xFF2A303F), onSurfaceVariant = Color(0xFFBFC8DC),
    surfaceContainer = Color(0xFF1D2230), surfaceContainerHigh = Color(0xFF252B3A),
    outline = Color(0xFF959FB5), outlineVariant = Color(0xFF41495B),
    tertiary = Color(0xFFFFBD58), tertiaryContainer = Color(0xFF54401F),
)

private enum class Section(val title: String, val icon: Int) {
    Articles("記事", R.drawable.ic_articles),
    Queues("キュー", R.drawable.ic_queues),
    Feeds("フィード", R.drawable.ic_rss),
    History("閲覧履歴", R.drawable.ic_history),
}

private enum class DetailKind { Article, Feed }
private data class DetailRoute(val kind: DetailKind, val id: Long)

private val detailPathSaver = listSaver<List<DetailRoute>, String>(
    save = { path -> path.map { "${it.kind.name}/${it.id}" } },
    restore = { path ->
        path.map { DetailRoute(DetailKind.valueOf(it.substringBefore('/')), it.substringAfter('/').toLong()) }
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QarryScreen(library: FeedLibrary) {
    var section by rememberSaveable { mutableStateOf(Section.Articles) }
    var queueId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailPath by rememberSaveable(stateSaver = detailPathSaver) { mutableStateOf(emptyList<DetailRoute>()) }
    var unread by rememberSaveable { mutableStateOf(false) }
    var unassignedOnly by rememberSaveable { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var feedOrder by rememberSaveable { mutableStateOf(FeedOrder.Name) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableIntStateOf(75) }
    var historyLimit by rememberSaveable { mutableIntStateOf(75) }
    var feedLimit by rememberSaveable { mutableIntStateOf(75) }
    var snapshot by remember { mutableStateOf<LibrarySnapshot?>(null) }
    var article by remember { mutableStateOf<Article?>(null) }
    var history by remember { mutableStateOf<ArticleListing?>(null) }
    var feedArticles by remember { mutableStateOf<Pair<Long, ArticleListing>?>(null) }
    var queueEditor by remember { mutableStateOf<QueueEditor?>(null) }
    var manageFeed by remember { mutableStateOf<Subscription?>(null) }
    var confirmDelete by remember { mutableStateOf<DeleteTarget?>(null) }
    var choosingOpml by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val revision by library.revision.collectAsState()
    val refreshing by library.refreshing.collectAsState()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val detail = detailPath.lastOrNull()
    val articleId = detail?.id?.takeIf { detail.kind == DetailKind.Article }
    val feedId = detail?.id?.takeIf { detail.kind == DetailKind.Feed }

    fun change(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: android.database.sqlite.SQLiteException) {
                snackbars.showSnackbar("端末への保存または読み込みに失敗しました。")
            }
        }
    }

    fun navigateTo(route: DetailRoute) {
        val index = detailPath.indexOf(route)
        detailPath = if (index >= 0) detailPath.take(index + 1) else detailPath + route
    }

    fun openArticle(opened: Article) {
        article = opened.copy(read = true)
        navigateTo(DetailRoute(DetailKind.Article, opened.id))
        change { library.recordView(opened.id) }
    }

    fun openFeed(id: Long) {
        val route = DetailRoute(DetailKind.Feed, id)
        if (route !in detailPath) feedLimit = 75
        navigateTo(route)
    }

    fun openLink(address: String) {
        webUrl(address)?.let { safeUrl ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, safeUrl.toUri()))
            } catch (_: ActivityNotFoundException) {
                change { snackbars.showSnackbar("URLを開けるアプリがありません。") }
            }
        }
    }

    suspend fun dismissArticle(selected: Article, rating: ArticleRating?): Boolean = try {
        library.dismissArticle(selected.id, rating)
        true
    } catch (_: android.database.sqlite.SQLiteException) {
        change { snackbars.showSnackbar("記事を保存または削除できませんでした。") }
        false
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        choosingOpml = false
        if (uri != null) {
            importing = true
            change {
                val message = try {
                    val imported = library.importOpml { context.contentResolver.openInputStream(uri) }
                    if (imported.feedIds.isNotEmpty()) change { library.refreshImported(imported.feedIds) }
                    buildList {
                        add(if (imported.feedIds.isEmpty()) "新しく登録するフィードはありません" else "${imported.feedIds.size}件を登録しました")
                        if (imported.duplicateCount > 0) add("重複${imported.duplicateCount}件")
                        if (imported.unsupportedCount > 0) add("対象外${imported.unsupportedCount}件")
                    }.joinToString("・")
                } catch (error: OpmlFormatException) {
                    error.message.orEmpty()
                } catch (_: IOException) {
                    "OPMLファイルを開けませんでした。"
                } catch (_: SecurityException) {
                    "OPMLファイルを開けませんでした。"
                } finally {
                    importing = false
                }
                snackbars.showSnackbar(message)
            }
        }
    }

    LaunchedEffect(revision, queueId, unread, limit) {
        try {
            val loaded = library.load(queueId, unread, limit)
            if (queueId != null && loaded.queues.none { it.id == queueId }) queueId = null
            snapshot = loaded
        } catch (_: android.database.sqlite.SQLiteException) {
            snackbars.showSnackbar("端末のデータを読み込めませんでした。")
        }
    }

    LaunchedEffect(articleId, feedId, section, revision, feedLimit, historyLimit) {
        try {
            when {
                articleId != null -> {
                    val loaded = library.article(articleId)
                    if (loaded == null) detailPath = detailPath.dropLast(1) else article = loaded
                }
                feedId != null -> feedArticles = feedId to library.feedArticles(feedId, feedLimit)
                section == Section.History -> history = library.history(historyLimit)
            }
        } catch (_: android.database.sqlite.SQLiteException) {
            snackbars.showSnackbar("端末のデータを読み込めませんでした。")
        }
    }

    fun chooseQueue(id: Long?) {
        queueId = id
        limit = 75
    }

    fun back() {
        when {
            adding -> adding = false
            detailPath.isNotEmpty() -> detailPath = detailPath.dropLast(1)
            section != Section.Articles -> section = Section.Articles
            else -> chooseQueue(null)
        }
    }

    BackHandler(adding || detail != null || section != Section.Articles || queueId != null) { back() }
    val fullScreen = adding || detail != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (fullScreen) {
                        Text(
                            when {
                                adding -> "フィードを追加"
                                feedId != null -> "フィード詳細"
                                else -> "記事"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.ic_qarry_foreground), null, Modifier.size(48.dp))
                            Text(
                                "Q-arry",
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (fullScreen) IconButton(onClick = ::back) {
                        Icon(painterResource(R.drawable.ic_back), "戻る")
                    }
                },
                actions = {
                    if (!fullScreen && snapshot?.subscriptions.orEmpty().isNotEmpty()) {
                        IconButton(onClick = { change { library.refresh() } }, enabled = !refreshing) {
                            Icon(painterResource(R.drawable.ic_refresh), "フィードを更新")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                },
            )
        },
        bottomBar = {
            if (!fullScreen) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    for (target in Section.entries) {
                        NavigationBarItem(
                            selected = section == target,
                            onClick = { section = target },
                            icon = { Icon(painterResource(target.icon), null) },
                            label = { Text(target.title) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (refreshing && !fullScreen) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val page = Modifier.widthIn(max = 760.dp).fillMaxSize()
                val content = snapshot
                when {
                    adding -> AddFeedPage(
                        library = library,
                        queueId = queueId.takeIf { section == Section.Articles },
                        subscribedUrls = content?.subscriptions.orEmpty().map { it.url }.toSet(),
                        modifier = page,
                        onAdded = {
                            adding = false
                            section = Section.Articles
                            change { snackbars.showSnackbar("フィードを登録しました") }
                        },
                    )
                    articleId != null -> article?.takeIf { it.id == articleId }?.let { opened ->
                        ArticlePage(
                            article = opened,
                            onFeed = { openFeed(opened.feedId) },
                            onRemove = {
                                scope.launch {
                                    if (dismissArticle(opened, null) && detailPath.lastOrNull() == detail) back()
                                }
                            },
                            onReadChange = { read -> change { library.markRead(opened.id, read) } },
                            onOpen = ::openLink,
                            modifier = page,
                        )
                    } ?: CircularProgressIndicator(Modifier.padding(28.dp))
                    content == null -> CircularProgressIndicator(Modifier.padding(28.dp))
                    feedId != null -> {
                        val feed = content.subscriptions.firstOrNull { it.id == feedId }
                        val listing = feedArticles?.takeIf { it.first == feedId }?.second
                        when {
                            feed == null -> EmptyState("フィードが見つかりません", null)
                            listing == null -> CircularProgressIndicator(Modifier.padding(28.dp))
                            else -> FeedDetailPage(
                                feed = feed,
                                queues = content.queues.filter { it.id in feed.queueIds },
                                listing = listing,
                                refreshing = refreshing,
                                onLink = ::openLink,
                                onQueue = { id ->
                                    chooseQueue(id)
                                    detailPath = emptyList()
                                    section = Section.Articles
                                },
                                onManage = { manageFeed = feed },
                                onFavoriteChange = { favorite -> change { library.setFeedFavorite(feed.id, favorite) } },
                                onRefresh = { change { library.refresh(feed.id) } },
                                onUnsubscribe = { confirmDelete = DeleteTarget.Feed(feed) },
                                onArticle = ::openArticle,
                                onDismiss = ::dismissArticle,
                                onMore = { feedLimit += 75 },
                                modifier = page,
                            )
                        }
                    }
                    section == Section.History -> history?.let { listing ->
                        HistoryPage(listing, ::openArticle, ::dismissArticle, onMore = { historyLimit += 75 }, modifier = page)
                    } ?: CircularProgressIndicator(Modifier.padding(28.dp))
                    section == Section.Articles -> ArticlesPage(
                        content, queueId, unread,
                        onQueue = ::chooseQueue,
                        onUnread = { unread = !unread; limit = 75 },
                        onAdd = { adding = true },
                        onMore = { limit += 75 },
                        onArticle = ::openArticle,
                        onDismiss = ::dismissArticle,
                        modifier = page,
                    )
                    section == Section.Queues -> QueuesPage(
                        content.queues,
                        onOpen = { chooseQueue(it.id); section = Section.Articles },
                        onAdd = { queueEditor = QueueEditor(null, "") },
                        onRename = { queueEditor = QueueEditor(it.id, it.name) },
                        onDelete = { confirmDelete = DeleteTarget.Queue(it) },
                        modifier = page,
                    )
                    else -> FeedsPage(
                        content, refreshing,
                        importing = importing,
                        unassignedOnly = unassignedOnly,
                        onUnassignedOnly = { unassignedOnly = !unassignedOnly },
                        favoritesOnly = favoritesOnly,
                        onFavoritesOnly = { favoritesOnly = !favoritesOnly },
                        order = feedOrder,
                        onOrder = { feedOrder = it },
                        onImport = {
                            if (!choosingOpml && !importing) {
                                choosingOpml = true
                                try {
                                    importPicker.launch(arrayOf("*/*"))
                                } catch (_: ActivityNotFoundException) {
                                    choosingOpml = false
                                    change { snackbars.showSnackbar("ファイルを選択できるアプリがありません。") }
                                }
                            }
                        },
                        onAdd = { adding = true },
                        onOpen = { openFeed(it.id) },
                        onManage = { manageFeed = it },
                        onRefresh = { feed -> change { library.refresh(feed.id) } },
                        onDelete = { confirmDelete = DeleteTarget.Feed(it) },
                        modifier = page,
                    )
                }
            }
        }
    }

    queueEditor?.let { editor ->
        QueueNameDialog(editor, onDismiss = { queueEditor = null }) { name ->
            try {
                if (library.nameQueue(editor.id, name)) {
                    queueEditor = null
                    null
                } else {
                    "同じ名前のキューがあるか、キューが削除されています。"
                }
            } catch (_: android.database.sqlite.SQLiteException) {
                "キューを保存できませんでした。"
            }
        }
    }
    manageFeed?.let { feed ->
        QueueMembershipDialog(
            feed = feed,
            queues = snapshot?.queues.orEmpty(),
            onDismiss = { manageFeed = null },
            onCreateQueue = { manageFeed = null; queueEditor = QueueEditor(null, "") },
            onSave = { selected ->
                change {
                    library.setMembership(feed.id, selected)
                    manageFeed = null
                }
            },
        )
    }
    confirmDelete?.let { target ->
        val isFeed = target is DeleteTarget.Feed
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (isFeed) "フィードを解除しますか？" else "キューを削除しますか？") },
            text = { Text(if (isFeed) "このフィードの記事とキューへの登録が端末から削除されます。" else "フィードと記事は購読一覧に残ります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    change {
                        when (target) {
                            is DeleteTarget.Feed -> {
                                library.removeFeed(target.feed.id)
                                if (feedId == target.feed.id) {
                                    detailPath = emptyList()
                                    section = Section.Feeds
                                }
                            }
                            is DeleteTarget.Queue -> {
                                library.removeQueue(target.queue.id)
                                if (queueId == target.queue.id) chooseQueue(null)
                            }
                        }
                    }
                }) { Text(if (isFeed) "解除" else "削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("キャンセル") } },
        )
    }
}

data class QueueEditor(val id: Long?, val name: String)

private sealed interface DeleteTarget {
    data class Feed(val feed: Subscription) : DeleteTarget
    data class Queue(val queue: FeedQueue) : DeleteTarget
}
