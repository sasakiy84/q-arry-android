package io.github.sasakiy84.qarry

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QarryScreen(library: FeedLibrary) {
    var section by rememberSaveable { mutableStateOf(Section.Articles) }
    var queueId by rememberSaveable { mutableStateOf<Long?>(null) }
    var articleId by rememberSaveable { mutableStateOf<Long?>(null) }
    var unread by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableIntStateOf(75) }
    var snapshot by remember { mutableStateOf<LibrarySnapshot?>(null) }
    var article by remember { mutableStateOf<Article?>(null) }
    var queueEditor by remember { mutableStateOf<QueueEditor?>(null) }
    var manageFeed by remember { mutableStateOf<Subscription?>(null) }
    var confirmDelete by remember { mutableStateOf<DeleteTarget?>(null) }
    val revision by library.revision.collectAsState()
    val refreshing by library.refreshing.collectAsState()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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

    LaunchedEffect(revision, queueId, unread, limit) {
        try {
            val loaded = library.load(queueId, unread, limit)
            if (queueId != null && loaded.queues.none { it.id == queueId }) queueId = null
            snapshot = loaded
        } catch (_: android.database.sqlite.SQLiteException) {
            snackbars.showSnackbar("端末のデータを読み込めませんでした。")
        }
    }

    LaunchedEffect(articleId, revision) {
        article = articleId?.let { library.article(it) }
    }

    fun chooseQueue(id: Long?) {
        queueId = id
        limit = 75
    }

    fun back() {
        when {
            adding -> adding = false
            articleId != null -> {
                article = null
                articleId = null
            }
            section != Section.Articles -> section = Section.Articles
            else -> chooseQueue(null)
        }
    }

    BackHandler(adding || articleId != null || section != Section.Articles || queueId != null) { back() }
    val detail = articleId != null
    val fullScreen = adding || detail
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (fullScreen) {
                        Text(if (adding) "フィードを追加" else "記事", style = MaterialTheme.typography.titleMedium)
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
                    detail -> article?.let { opened ->
                        ArticlePage(
                            article = opened,
                            onReadChange = { read -> change { library.markRead(opened.id, read) } },
                            onOpen = { address ->
                                webUrl(address)?.let { safeUrl ->
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, safeUrl.toUri()))
                                    } catch (_: ActivityNotFoundException) {
                                        change { snackbars.showSnackbar("URLを開けるアプリがありません。") }
                                    }
                                }
                            },
                            modifier = page,
                        )
                    } ?: CircularProgressIndicator(Modifier.padding(28.dp))
                    content == null -> CircularProgressIndicator(Modifier.padding(28.dp))
                    section == Section.Articles -> ArticlesPage(
                        content, queueId, unread,
                        onQueue = ::chooseQueue,
                        onUnread = { unread = !unread; limit = 75 },
                        onAdd = { adding = true },
                        onMore = { limit += 75 },
                        onArticle = { opened ->
                            article = opened.copy(read = true)
                            articleId = opened.id
                            if (!opened.read) change { library.markRead(opened.id, true) }
                        },
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
                        onAdd = { adding = true },
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
                            is DeleteTarget.Feed -> library.removeFeed(target.feed.id)
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
