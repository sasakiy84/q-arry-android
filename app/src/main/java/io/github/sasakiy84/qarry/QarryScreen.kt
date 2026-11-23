package io.github.sasakiy84.qarry

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class Section(val title: String, val icon: Int) {
    Articles("記事", R.drawable.ic_articles),
    Queues("キュー", R.drawable.ic_queues),
    Feeds("フィード", R.drawable.ic_rss),
    Settings("設定", R.drawable.ic_settings),
}

private enum class DetailKind { Article, Feed, Queue }
private data class DetailRoute(val kind: DetailKind, val id: Long)
private data class ArticleListFilter(val queueId: Long?, val unreadOnly: Boolean, val unassignedOnly: Boolean)

private fun DetailRoute.stateKey(index: Int): String = "detail-$index-${kind.name}-$id"

private val detailPathSaver = listSaver<List<DetailRoute>, String>(
    save = { path -> path.map { "${it.kind.name}/${it.id}" } },
    restore = { path ->
        path.map { DetailRoute(DetailKind.valueOf(it.substringBefore('/')), it.substringAfter('/').toLong()) }
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QarryScreen(
    library: FeedLibrary,
    notificationFeedId: Long? = null,
    onNotificationOpened: () -> Unit = {},
    quoteLearning: QuoteLearning = library.quotes,
    onArticleOpenMode: (ArticleOpenMode) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(Section.Articles) }
    var queueId by rememberSaveable { mutableStateOf<Long?>(null) }
    var articlesUnassignedOnly by rememberSaveable { mutableStateOf(false) }
    var detailPath by rememberSaveable(stateSaver = detailPathSaver) { mutableStateOf(emptyList<DetailRoute>()) }
    var unread by rememberSaveable { mutableStateOf(true) }
    var markingRead by remember { mutableStateOf(false) }
    var feedsUnassignedOnly by rememberSaveable { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var feedOrder by rememberSaveable { mutableStateOf(FeedOrder.Name) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var quoteTraining by rememberSaveable { mutableStateOf(false) }
    var archivingArticle by remember { mutableStateOf<Long?>(null) }
    var editingWebsiteScope by rememberSaveable { mutableStateOf(false) }
    var savingFeedSetup by remember { mutableStateOf(false) }
    var limit by rememberSaveable { mutableIntStateOf(75) }
    var settingsTab by rememberSaveable { mutableStateOf(SettingsTab.General) }
    var historyLimit by rememberSaveable { mutableIntStateOf(75) }
    var ratingLimit by rememberSaveable { mutableIntStateOf(75) }
    val listStates = rememberSaveableStateHolder()
    var snapshot by remember { mutableStateOf<LibrarySnapshot?>(null) }
    var loadedArticleFilter by remember { mutableStateOf<ArticleListFilter?>(null) }
    var article by remember { mutableStateOf<Article?>(null) }
    var history by remember { mutableStateOf<ArticleListing?>(null) }
    var pendingArticleView by remember { mutableStateOf<Job?>(null) }
    var ratings by remember { mutableStateOf<RatingListing?>(null) }
    val dialogs = rememberLibraryDialogState()
    val revision by library.revision.collectAsState()
    val archiveRevision by library.archive.revision.collectAsState()
    val refreshing by library.refreshing.collectAsState()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val quotePreferences = remember(context) { context.getSharedPreferences("quote_display", Context.MODE_PRIVATE) }
    var quoteEnabled by remember(quotePreferences) { mutableStateOf(quotePreferences.getBoolean("enabled", false)) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val articleOpenMode = LocalArticleOpenMode.current
    val notificationSettings = rememberNotificationSettings()
    val articleFilter = ArticleListFilter(queueId, unread, articlesUnassignedOnly)
    val detail = detailPath.lastOrNull()
    val articleId = detail?.id?.takeIf { detail.kind == DetailKind.Article }
    val feedId = detail?.id?.takeIf { detail.kind == DetailKind.Feed }
    val openedQueueId = detail?.id?.takeIf { detail.kind == DetailKind.Queue }
    val sourceQueueId = detailPath.lastOrNull { it.kind != DetailKind.Article }
        ?.takeIf { it.kind == DetailKind.Queue }?.id
        ?: queueId.takeIf { !searching && section == Section.Articles && detailPath.all { it.kind == DetailKind.Article } }

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
        detailPath.forEachIndexed { index, route ->
            if (route != next.getOrNull(index)) listStates.removeState(route.stateKey(index))
        }
        detailPath = next
    }

    fun navigateTo(route: DetailRoute) {
        if (detailPath.lastOrNull() == route) return
        updateDetailPath(detailPath + route)
    }

    LaunchedEffect(notificationFeedId) {
        val id = notificationFeedId ?: return@LaunchedEffect
        adding = false
        searching = false
        quoteTraining = false
        editingWebsiteScope = false
        dialogs.queueEditor = null
        dialogs.managedFeedId = null
        dialogs.filteredFeedId = null
        dialogs.confirmDelete = null
        section = Section.Feeds
        updateDetailPath(listOf(DetailRoute(DetailKind.Feed, id)))
        onNotificationOpened()
    }

    fun openArticle(id: Long?, url: String?, press: ArticlePress, summary: Article? = null, archivedKey: RatingKey? = null) {
        val browser = articleOpenMode.forPress(press) == ArticleOpenMode.Browser || id == null
        if (browser && url != null && context.openArticleInBrowser(url)) {
            pendingArticleView = change {
                if (id != null) library.recordOriginalOpen(id, sourceQueueId)
                else if (archivedKey != null) library.recordArchivedOriginalOpen(archivedKey, url)
            }
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

    fun openFeed(id: Long) = navigateTo(DetailRoute(DetailKind.Feed, id))

    fun openQueue(id: Long) = navigateTo(DetailRoute(DetailKind.Queue, id))

    fun openLink(address: String): Boolean {
        val safeUrl = webUrl(address) ?: return false
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, safeUrl.toUri()))
            return true
        } catch (_: ActivityNotFoundException) {
            change { snackbars.showSnackbar("URLを開けるアプリがありません。") }
        } catch (_: SecurityException) {
            change { snackbars.showSnackbar("URLを開けませんでした。") }
        }
        return false
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
        library.dismissArticle(selected.id, rating, sourceQueueId)
        true
    } catch (_: android.database.sqlite.SQLiteException) {
        change { snackbars.showSnackbar("記事を保存または削除できませんでした。") }
        false
    }

    val transfer = rememberLibraryTransfer(library, snackbars, ::change)

    LaunchedEffect(revision, articleFilter, limit) {
        try {
            val (requestedQueue, unreadOnly, unassignedOnly) = articleFilter
            val loaded = library.load(requestedQueue, unreadOnly, limit, unassignedOnly)
            if (requestedQueue != null && loaded.queues.none { it.id == requestedQueue }) queueId = null
            snapshot = loaded
            loadedArticleFilter = articleFilter
        } catch (_: android.database.sqlite.SQLiteException) {
            snackbars.showSnackbar("端末のデータを読み込めませんでした。")
        }
    }

    LaunchedEffect(articleId, detail, adding, section, revision, archiveRevision.takeIf { articleId != null }, settingsTab, historyLimit, ratingLimit) {
        try {
            when {
                articleId != null -> {
                    val loaded = library.article(articleId)
                    if (loaded == null) updateDetailPath(detailPath.dropLast(1)) else article = loaded
                }
                detail == null && !adding && section == Section.Settings -> when (settingsTab) {
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

    fun chooseQueue(id: Long?, unassignedOnly: Boolean = false) {
        if (queueId == id && articlesUnassignedOnly == unassignedOnly) return
        queueId = id
        articlesUnassignedOnly = unassignedOnly
        limit = 75
    }

    fun markAllRead(selectedQueue: Long?, unassignedOnly: Boolean = false) {
        if (markingRead) return
        markingRead = true
        change {
            val count = try {
                library.markAllRead(selectedQueue, unassignedOnly)
            } finally {
                markingRead = false
            }
            snackbars.showSnackbar(if (count > 0) "${count}件を既読にしました" else "未読の記事はありません")
        }
    }

    fun back() {
        when {
            savingFeedSetup -> Unit
            adding -> adding = false
            editingWebsiteScope -> editingWebsiteScope = false
            quoteTraining -> quoteTraining = false
            detailPath.isNotEmpty() -> updateDetailPath(detailPath.dropLast(1))
            searching -> searching = false
            section != Section.Articles -> section = Section.Articles
        }
    }

    BackHandler(adding || searching || quoteTraining || detail != null || section != Section.Articles) { back() }
    val fullScreen = adding || searching || quoteTraining || detail != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (fullScreen) {
                        Text(
                            when {
                                adding -> "フィードを追加"
                                quoteTraining -> QUOTE_TRAINING_TITLE
                                editingWebsiteScope -> "監視範囲を変更"
                                feedId != null -> "フィード詳細"
                                openedQueueId != null -> "キュー"
                                searching && detail == null -> "記事検索"
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
                    if (fullScreen) IconButton(
                        onClick = { backDispatcher?.onBackPressed() }, enabled = !savingFeedSetup,
                    ) {
                        Icon(painterResource(R.drawable.ic_back), "戻る")
                    }
                },
                actions = {
                    if (!fullScreen && section == Section.Articles) {
                        IconButton(onClick = { searching = true }) {
                            Icon(painterResource(R.drawable.ic_search), "記事検索")
                        }
                    }
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
                    quoteTraining -> QuoteTrainingPage(quoteLearning, page)
                    adding -> AddFeedPage(
                        library = library,
                        queueId = openedQueueId ?: queueId.takeIf { section == Section.Articles },
                        subscribedUrls = content?.subscriptions.orEmpty().map { it.url }.toSet(),
                        saving = savingFeedSetup,
                        onSavingChange = { savingFeedSetup = it },
                        modifier = page,
                        onAdded = {
                            adding = false
                            change { snackbars.showSnackbar("フィードを登録しました") }
                        },
                    )
                    articleId != null -> listStates.SaveableStateProvider(checkNotNull(detail).stateKey(detailPath.lastIndex)) {
                        val opened = article?.takeIf { it.id == articleId }
                        if (opened == null) LoadingIndicator("記事を読み込み中") else {
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
                                onOpen = { url ->
                                    if (openLink(url)) {
                                        pendingArticleView = change { library.recordOriginalOpen(opened.id, sourceQueueId) }
                                    }
                                },
                                onShare = ::shareArticle,
                                onFetchFullText = if (opened.archivedBody == null && httpsUrl(opened.url) != null) ({
                                    if (archivingArticle == null) {
                                        archivingArticle = opened.id
                                        change {
                                            val saved = try {
                                                library.archive.fetchArticle(opened)
                                            } finally {
                                                archivingArticle = null
                                            }
                                            snackbars.showSnackbar(if (saved) "本文を保存しました" else "本文を取得できませんでした")
                                        }
                                    }
                                }) else null,
                                fetchingFullText = archivingArticle == opened.id,
                                quoteLearning = quoteLearning.takeIf { quoteEnabled },
                                modifier = page,
                            )
                        }
                    }
                    searching && detail == null -> listStates.SaveableStateProvider("article-search") {
                        ArticleSearchPage(library.archive, revision, onArticle, page)
                    }
                    section == Section.Settings && detail == null -> listStates.SaveableStateProvider("settings-${settingsTab.name}") {
                        SettingsPage(
                            tab = settingsTab,
                            onTab = { settingsTab = it },
                            articleOpenMode = articleOpenMode,
                            onArticleOpenMode = onArticleOpenMode,
                            notificationSettings = notificationSettings,
                            quoteEnabled = quoteEnabled,
                            onQuoteEnabled = { enabled ->
                                quoteEnabled = enabled
                                quotePreferences.edit { putBoolean("enabled", enabled) }
                            },
                            onQuoteTraining = { quoteTraining = true },
                            transfer = transfer,
                            archive = library.archive,
                            libraryRevision = revision,
                            viewed = history,
                            rated = ratings,
                            onArticle = onArticle,
                            onRatedArticle = { rated, press -> openArticle(rated.articleId, rated.url, press, archivedKey = rated.key) },
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
                    openedQueueId != null -> {
                        val route = checkNotNull(detail)
                        if (content.queues.none { it.id == openedQueueId }) {
                            LaunchedEffect(route) { back() }
                        } else listStates.SaveableStateProvider(route.stateKey(detailPath.lastIndex)) {
                            QueueArticlesPage(
                                library, openedQueueId, revision, markingRead,
                                onMarkAllRead = { markAllRead(openedQueueId) },
                                onDismissQueueArticles = { unreadOnly ->
                                    dialogs.confirmDelete = DeleteTarget.QueueArticles(openedQueueId, unreadOnly)
                                },
                                onAdd = { adding = true }, onArticle = onArticle, onDismiss = ::dismissArticle,
                                modifier = page,
                            )
                        }
                    }
                    feedId != null -> {
                        val feed = content.subscriptions.firstOrNull { it.id == feedId }
                        val route = checkNotNull(detail)
                        when {
                            feed == null -> LaunchedEffect(route) {
                                editingWebsiteScope = false
                                back()
                            }
                            editingWebsiteScope -> EditWebsiteScopePage(
                                library = library,
                                feed = feed,
                                saving = savingFeedSetup,
                                onSavingChange = { savingFeedSetup = it },
                                onCancel = { editingWebsiteScope = false },
                                onSaved = {
                                    editingWebsiteScope = false
                                    change { snackbars.showSnackbar("監視範囲を変更しました") }
                                },
                                modifier = page,
                            )
                            else -> listStates.SaveableStateProvider(route.stateKey(detailPath.lastIndex)) {
                                FeedDetailPage(
                                    feed = feed,
                                    queues = content.queues.filter { it.id in feed.queueIds },
                                    library = library,
                                    revision = revision,
                                    refreshing = refreshing,
                                    onLink = { openLink(it) },
                                    onQueue = ::openQueue,
                                    onManage = { dialogs.managedFeedId = feed.id },
                                    onKeywordFilter = { dialogs.filteredFeedId = feed.id },
                                    onFavoriteChange = { favorite -> change { library.setFeedFavorite(feed.id, favorite) } },
                                    notificationSettings = notificationSettings,
                                    onNotificationModeChange = { mode ->
                                        change {
                                            library.setFeedNotifications(feed.id, mode)
                                            if (mode.enabled(notificationSettings.defaultEnabled)) {
                                                notificationSettings.requestPermission()
                                            }
                                        }
                                    },
                                    onRefresh = { change { library.refresh(feed.id) } },
                                    onChangeWebsiteScope = { editingWebsiteScope = true },
                                    onUnsubscribe = { dialogs.confirmDelete = DeleteTarget.Feed(feed.id) },
                                    onDismissFeedArticles = { dialogs.confirmDelete = DeleteTarget.FeedArticles(feed.id) },
                                    onArticle = onArticle,
                                    onDismiss = ::dismissArticle,
                                    modifier = page,
                                )
                            }
                        }
                    }
                    section == Section.Articles -> listStates.SaveableStateProvider("articles") {
                        ArticlesPage(
                            content, queueId, unread,
                            markingRead = markingRead,
                            onQueueFilter = ::chooseQueue,
                            onUnread = { unread = !unread; limit = 75 },
                            onMarkAllRead = { markAllRead(queueId, articlesUnassignedOnly) },
                            onAdd = { adding = true },
                            onMore = { limit += 75 },
                            onArticle = onArticle,
                            onDismiss = ::dismissArticle,
                            modifier = page,
                            loading = loadedArticleFilter != articleFilter,
                            unassignedOnly = articlesUnassignedOnly,
                            onDismissQueueArticles = queueId?.let { selectedQueue ->
                                { unreadOnly -> dialogs.confirmDelete = DeleteTarget.QueueArticles(selectedQueue, unreadOnly) }
                            },
                        )
                    }
                    section == Section.Queues -> listStates.SaveableStateProvider("queues") {
                        QueuesPage(
                            content.queues,
                            onOpen = { openQueue(it.id) },
                            onAdd = { dialogs.queueEditor = QueueEditor(null, "") },
                            onRename = { dialogs.queueEditor = QueueEditor(it.id, it.name) },
                            onDelete = { dialogs.confirmDelete = DeleteTarget.Queue(it.id) },
                            modifier = page,
                        )
                    }
                    else -> listStates.SaveableStateProvider("feeds") {
                        FeedsPage(
                            content, refreshing,
                            unassignedOnly = feedsUnassignedOnly,
                            onUnassignedOnly = { feedsUnassignedOnly = !feedsUnassignedOnly },
                            favoritesOnly = favoritesOnly,
                            onFavoritesOnly = { favoritesOnly = !favoritesOnly },
                            order = feedOrder,
                            onOrder = { feedOrder = it },
                            onAdd = { adding = true },
                            onOpen = { openFeed(it.id) },
                            onManage = { dialogs.managedFeedId = it.id },
                            onRefresh = { feed -> change { library.refresh(feed.id) } },
                            onDelete = { dialogs.confirmDelete = DeleteTarget.Feed(it.id) },
                            modifier = page,
                        )
                    }
                }
            }
        }
    }

    LibraryDialogs(dialogs, snapshot, listStates, library, ::change, onArticlesRemoved = { count ->
        snackbars.showSnackbar(if (count == 0) "削除する記事はありません" else "${count}件をすべてのキューから削除しました")
    }) { removedQueueId ->
        if (queueId == removedQueueId) chooseQueue(null)
    }
}
