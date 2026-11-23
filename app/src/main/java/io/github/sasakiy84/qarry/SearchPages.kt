package io.github.sasakiy84.qarry

import android.database.sqlite.SQLiteException
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
internal fun ArticleSearchPage(
    archive: ArticleArchive,
    libraryRevision: Int,
    onArticle: (Article, ArticlePress) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableIntStateOf(60) }
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<ArticleSearchResult?>(null) }
    var failed by remember { mutableStateOf(false) }
    val archiveRevision by archive.revision.collectAsState()
    val focus = LocalFocusManager.current
    fun submit() {
        focus.clearFocus()
        result = null
        failed = false
        query = input.trim()
        limit = 60
        attempt++
    }
    LaunchedEffect(query, limit, libraryRevision, archiveRevision, attempt) {
        if (query.isBlank()) {
            result = null
            return@LaunchedEffect
        }
        failed = false
        try {
            result = archive.search(query, limit)
        } catch (_: SQLiteException) {
            failed = true
        }
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(200) },
                modifier = Modifier.weight(1f),
                label = { Text("タイトル・本文") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
            Button(onClick = ::submit, enabled = input.isNotBlank()) { Text("検索") }
        }
        val loaded = result
        when {
            query.isBlank() -> Unit
            failed -> EmptyState("検索できませんでした", null, { attempt++ }, "再試行")
            loaded == null -> LoadingIndicator("検索中")
            else -> {
                if (loaded.semanticUnavailable) {
                    Text("キーワード検索の結果を表示しています。", Modifier.padding(horizontal = 16.dp).announceStatus(),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (loaded.listing.articles.isEmpty()) {
                    EmptyState("該当する記事がありません", null)
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(loaded.listing.articles, key = { it.id }) { article ->
                            ListItem(
                                headlineContent = { Text(article.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                overlineContent = { Text(article.feedTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { article.body?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                                modifier = Modifier.articleOpenActions(article.url, { press -> onArticle(article, press) }),
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                        if (loaded.listing.hasMore) {
                            moreArticles { limit += 60 }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArticleSearchSettings(archive: ArticleArchive, libraryRevision: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val archiveRevision by archive.revision.collectAsState()
    var stats by remember { mutableStateOf<SearchStats?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    val working = progress != null
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(libraryRevision, archiveRevision, working) {
        if (!working) {
            try {
                stats = archive.stats()
            } catch (_: SQLiteException) {
                message = "保存容量を確認できませんでした。"
            }
        }
    }
    fun perform(status: String, operation: suspend () -> String?) {
        progress = status
        message = null
        scope.launch {
            try {
                message = operation()
            } catch (_: IOException) {
                message = "モデルを取得または読み込みできませんでした。"
            } catch (_: SQLiteException) {
                message = "端末に保存できませんでした。"
            } finally {
                progress = null
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("全文検索", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        stats?.let { status ->
            fun size(bytes: Long?): String = bytes?.let { Formatter.formatFileSize(context, it) } ?: "計測不可"
            Text("保存済み本文 ${status.archived} / ${status.articles}件 · ${size(status.archiveBytes)}")
            Text("全文インデックス ${status.indexed}件 · ${size(status.lexicalBytes)}")
            Text("ベクトルインデックス ${status.vectors}件 · ${size(status.vectorBytes)}")
            if (status.modelBytes > 0) Text("モデル ${size(status.modelBytes)}")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("ベクトル検索")
                    Text("初回は約157 MiBのモデルをダウンロードします。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = status.semanticEnabled,
                    onCheckedChange = { enabled ->
                        perform(if (enabled) "ベクトル検索を準備中" else "ベクトル検索を停止中") {
                            archive.enableSemanticSearch(enabled)
                            null
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.semantics { contentDescription = "ベクトル検索" },
                )
            }
        }
        OutlinedButton(onClick = {
            perform("本文を取得中") { "本文を${archive.fetchPending()}件保存しました。" }
        }, enabled = !working) {
            Text("本文を取得")
        }
        progress?.let { Text(it, Modifier.announceStatus(), style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, Modifier.announceStatus(), style = MaterialTheme.typography.bodySmall) }
    }
}
