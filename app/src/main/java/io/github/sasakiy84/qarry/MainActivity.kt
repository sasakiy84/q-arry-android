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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
                val preference = remember { ArticleOpeningPreference(this@MainActivity) }
                var mode by remember { mutableStateOf(preference.mode) }
                CompositionLocalProvider(LocalArticleOpenMode provides mode) {
                    QarryScreen(feedLibrary()) { updated ->
                        preference.mode = updated
                        mode = updated
                    }
                }
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
    Settings("設定", R.drawable.ic_settings),
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
private fun QarryScreen(library: FeedLibrary, onArticleOpenMode: (ArticleOpenMode) -> Unit) {
    var section by rememberSaveable { mutableStateOf(Section.Articles) }
    var queueId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailPath by rememberSaveable(stateSaver = detailPathSaver) { mutableStateOf(emptyList<DetailRoute>()) }
    var unread by rememberSaveable { mutableStateOf(true) }
    var markingRead by remember { mutableStateOf(false) }
    var unassignedOnly by rememberSaveable { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var feedOrder by rememberSaveable { mutableStateOf(FeedOrder.Name) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableIntStateOf(75) }
    var settingsTab by rememberSaveable { mutableStateOf(SettingsTab.General) }
    var historyLimit by rememberSaveable { mutableIntStateOf(75) }
    var ratingLimit by rememberSaveable { mutableIntStateOf(75) }
    var feedLimit by rememberSaveable { mutableIntStateOf(75) }
    var unfilteredFeedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val listStates = rememberSaveableStateHolder()
    var snapshot by remember { mutableStateOf<LibrarySnapshot?>(null) }
    var article by remember { mutableStateOf<Article?>(null) }
    var history by remember { mutableStateOf<ArticleListing?>(null) }
    var pendingArticleView by remember { mutableStateOf<Job?>(null) }
    var ratings by remember { mutableStateOf<RatingListing?>(null) }
    var feedArticles by remember { mutableStateOf<Pair<Long, ArticleListing>?>(null) }
    var queueEditor by remember { mutableStateOf<QueueEditor?>(null) }
    var manageFeed by remember { mutableStateOf<Subscription?>(null) }
    var filterFeed by remember { mutableStateOf<Subscription?>(null) }
    var confirmDelete by remember { mutableStateOf<DeleteTarget?>(null) }
    var choosingOpml by rememberSaveable { mutableStateOf(false) }
    var opmlTransfer by remember { mutableStateOf<OpmlTransfer?>(null) }
    val revision by library.revision.collectAsState()
    val refreshing by library.refreshing.collectAsState()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val articleOpenMode = LocalArticleOpenMode.current
    val detail = detailPath.lastOrNull()
    val articleId = detail?.id?.takeIf { detail.kind == DetailKind.Article }
    val feedId = detail?.id?.takeIf { detail.kind == DetailKind.Feed }
    val showingUnfiltered = feedId != null && feedId == unfilteredFeedId

    fun change(action: suspend () -> Unit): Job = scope.launch {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (_: android.database.sqlite.SQLiteException) {
            snackbars.showSnackbar("端末への保存または読み込みに失敗しました。")
        }
    }

    fun updateDetailPath(next: List<DetailRoute>) {
        detailPath.filter { it.kind == DetailKind.Feed && it !in next }
            .forEach { listStates.removeState("feed-${it.id}") }
        detailPath = next
    }

    fun navigateTo(route: DetailRoute) {
        if (detailPath.lastOrNull() == route) return
        val index = if (route.kind == DetailKind.Feed) detailPath.indexOf(route) else -1
        updateDetailPath(if (index >= 0) detailPath.take(index + 1) else detailPath + route)
    }

    fun openArticle(id: Long?, url: String?, press: ArticlePress, summary: Article? = null) {
        val browser = articleOpenMode.forPress(press) == ArticleOpenMode.Browser || id == null
        if (browser && url != null && context.openArticleInBrowser(url)) {
            if (id != null) change { library.recordView(id) }
        } else if (id != null) {
            if (section == Section.Settings && settingsTab == SettingsTab.Viewed && detailPath.isEmpty()) history = null
            if (summary != null) article = summary.copy(read = true)
            navigateTo(DetailRoute(DetailKind.Article, id))
            pendingArticleView = change { library.recordView(id) }
        } else {
            change { snackbars.showSnackbar("記事を開けませんでした。") }
        }
    }

    val onArticle: (Article, ArticlePress) -> Unit = { opened, press -> openArticle(opened.id, opened.url, press, opened) }

    fun openFeed(id: Long) {
        val route = DetailRoute(DetailKind.Feed, id)
        if (route !in detailPath) {
            feedLimit = 75
            unfilteredFeedId = null
        }
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

    fun shareArticle(title: String, address: String) {
        val chooser = articleShareIntent(title, address) ?: return
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            change { snackbars.showSnackbar("記事を共有できませんでした。") }
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
            opmlTransfer = OpmlTransfer.Importing
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
                    opmlTransfer = null
                }
                snackbars.showSnackbar(message)
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        choosingOpml = false
        if (uri != null) {
            opmlTransfer = OpmlTransfer.Exporting
            change {
                val message = try {
                    val exported = library.exportOpml { context.contentResolver.openOutputStream(uri, "wt") }
                    "${exported}件のフィードをエクスポートしました。"
                } catch (error: OpmlFormatException) {
                    error.message.orEmpty()
                } catch (_: IOException) {
                    "OPMLファイルを保存できませんでした。"
                } catch (_: SecurityException) {
                    "OPMLファイルを保存できませんでした。"
                } finally {
                    opmlTransfer = null
                }
                snackbars.showSnackbar(message)
            }
        }
    }

    fun chooseOpml(launchPicker: () -> Unit) {
        if (choosingOpml || opmlTransfer != null) return
        choosingOpml = true
        try {
            launchPicker()
        } catch (_: ActivityNotFoundException) {
            choosingOpml = false
            change { snackbars.showSnackbar("ファイルを選択できるアプリがありません。") }
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

    LaunchedEffect(articleId, feedId, section, revision, feedLimit, showingUnfiltered, settingsTab, historyLimit, ratingLimit) {
        try {
            when {
                articleId != null -> {
                    val loaded = library.article(articleId)
                    if (loaded == null) updateDetailPath(detailPath.dropLast(1)) else article = loaded
                }
                feedId != null -> feedArticles = feedId to library.feedArticles(
                    feedId, feedLimit, applyKeywordFilter = !showingUnfiltered,
                )
                section == Section.Settings -> when (settingsTab) {
                    SettingsTab.General -> Unit
                    SettingsTab.Viewed -> {
                        // An article can move to the top of history when viewed. Restore the list against its new order.
                        pendingArticleView?.join()
                        history = library.history(historyLimit)
                    }
                    SettingsTab.Rated -> ratings = library.ratingHistory(ratingLimit)
                }
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
            detailPath.isNotEmpty() -> updateDetailPath(detailPath.dropLast(1))
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
                            label = { Text(target.title, textAlign = TextAlign.Center, maxLines = 2) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (refreshing && !fullScreen) {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "フィードを更新中" })
            }
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
                                val openedFrom = detailPath
                                scope.launch {
                                    if (dismissArticle(opened, null) && detailPath == openedFrom) back()
                                }
                            },
                            onRate = { rating -> change { dismissArticle(opened, rating) } },
                            onReadChange = { read -> change { library.markRead(opened.id, read) } },
                            onOpen = ::openLink,
                            onShare = ::shareArticle,
                            modifier = page,
                        )
                    } ?: LoadingIndicator("記事を読み込み中")
                    section == Section.Settings && detail == null -> listStates.SaveableStateProvider("settings-${settingsTab.name}") {
                        SettingsPage(
                            tab = settingsTab,
                            onTab = { settingsTab = it },
                            articleOpenMode = articleOpenMode,
                            onArticleOpenMode = onArticleOpenMode,
                            transfer = opmlTransfer,
                            choosingFile = choosingOpml,
                            onImport = { chooseOpml { importPicker.launch(arrayOf("*/*")) } },
                            onExport = { chooseOpml { exportPicker.launch("q-arry-feeds.opml") } },
                            viewed = history,
                            rated = ratings,
                            onArticle = onArticle,
                            onRatedArticle = { rated, press -> openArticle(rated.articleId, rated.url, press) },
                            onDismiss = ::dismissArticle,
                            onChangeRating = { key, rating -> change { library.changeRating(key, rating) } },
                            onMore = {
                                when (settingsTab) {
                                    SettingsTab.General -> Unit
                                    SettingsTab.Viewed -> historyLimit += 75
                                    SettingsTab.Rated -> ratingLimit += 75
                                }
                            },
                            modifier = page,
                        )
                    }
                    content == null -> LoadingIndicator("購読情報を読み込み中")
                    feedId != null -> {
                        val feed = content.subscriptions.firstOrNull { it.id == feedId }
                        val listing = feedArticles?.takeIf { it.first == feedId }?.second
                        when {
                            feed == null -> EmptyState("フィードが見つかりません", null)
                            listing == null -> LoadingIndicator("フィードの記事を読み込み中")
                            else -> listStates.SaveableStateProvider("feed-${feed.id}") {
                                FeedDetailPage(
                                    feed = feed,
                                    queues = content.queues.filter { it.id in feed.queueIds },
                                    listing = listing,
                                    refreshing = refreshing,
                                    onLink = ::openLink,
                                    onQueue = { id ->
                                        chooseQueue(id)
                                        updateDetailPath(emptyList())
                                        section = Section.Articles
                                    },
                                    onManage = { manageFeed = feed },
                                    onKeywordFilter = { filterFeed = feed },
                                    showingUnfiltered = showingUnfiltered,
                                    onToggleUnfiltered = {
                                        unfilteredFeedId = if (showingUnfiltered) null else feed.id
                                        feedLimit = 75
                                    },
                                    onFavoriteChange = { favorite -> change { library.setFeedFavorite(feed.id, favorite) } },
                                    onRefresh = { change { library.refresh(feed.id) } },
                                    onUnsubscribe = { confirmDelete = DeleteTarget.Feed(feed) },
                                    onArticle = onArticle,
                                    onDismiss = ::dismissArticle,
                                    onMore = { feedLimit += 75 },
                                    modifier = page,
                                )
                            }
                        }
                    }
                    section == Section.Articles -> listStates.SaveableStateProvider("articles") {
                        key(queueId, unread) {
                            ArticlesPage(
                                content, queueId, unread,
                                markingRead = markingRead,
                                onQueue = ::chooseQueue,
                                onUnread = { unread = !unread; limit = 75 },
                                onMarkAllRead = {
                                    val selectedQueue = queueId
                                    markingRead = true
                                    change {
                                        val count = try {
                                            library.markAllRead(selectedQueue)
                                        } finally {
                                            markingRead = false
                                        }
                                        snackbars.showSnackbar(
                                            if (count > 0) "${count}件を既読にしました" else "未読の記事はありません",
                                        )
                                    }
                                },
                                onAdd = { adding = true },
                                onMore = { limit += 75 },
                                onArticle = onArticle,
                                onDismiss = ::dismissArticle,
                                modifier = page,
                            )
                        }
                    }
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
                        unassignedOnly = unassignedOnly,
                        onUnassignedOnly = { unassignedOnly = !unassignedOnly },
                        favoritesOnly = favoritesOnly,
                        onFavoritesOnly = { favoritesOnly = !favoritesOnly },
                        order = feedOrder,
                        onOrder = { feedOrder = it },
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
            onSave = { selected, filters ->
                try {
                    library.setMembership(feed.id, selected, filters)
                    manageFeed = null
                    null
                } catch (_: android.database.sqlite.SQLiteException) {
                    "キュー設定を保存できませんでした。"
                }
            },
        )
    }
    filterFeed?.let { feed ->
        KeywordFilterDialog(
            scopeLabel = "フィード全体",
            initial = feed.keywordFilter,
            replaceMessage = if (feed.queueKeywordFilters.isNotEmpty()) "保存すると、このフィードのキュー別フィルターを解除します。" else null,
            onDismiss = { filterFeed = null },
            onSave = { rule ->
                try {
                    library.setFeedKeywordFilter(feed.id, rule)
                    unfilteredFeedId = null
                    feedLimit = 75
                    filterFeed = null
                    null
                } catch (_: android.database.sqlite.SQLiteException) {
                    "フィルターを保存できませんでした。"
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
                                    updateDetailPath(emptyList())
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
