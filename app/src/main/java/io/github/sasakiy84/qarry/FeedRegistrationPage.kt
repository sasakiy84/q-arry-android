package io.github.sasakiy84.qarry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun AddFeedPage(
    library: FeedLibrary,
    queueId: Long?,
    subscribedUrls: Set<String>,
    saving: Boolean,
    onSavingChange: (Boolean) -> Unit,
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var searchedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(searchedUrl != null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf(FeedDiscovery()) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val busy = searching || saving

    fun search() {
        if (url.isBlank() || busy) return
        focus.clearFocus()
        searching = true
        error = null
        found = FeedDiscovery()
        searchedUrl = url.trim()
        attempt++
    }

    LaunchedEffect(searchedUrl, attempt) {
        val address = searchedUrl ?: return@LaunchedEffect
        searching = true
        error = null
        try {
            found = library.search(address)
        } catch (failure: FeedRequestException) {
            error = failure.message
        } finally {
            searching = false
        }
    }

    fun save(feed: FeedDocument) {
        if (busy) return
        onSavingChange(true)
        error = null
        scope.launch {
            try {
                library.subscribe(feed, queueId)
                onAdded()
            } catch (_: android.database.sqlite.SQLiteException) {
                error = "フィードを端末に保存できませんでした。"
            } finally {
                onSavingChange(false)
            }
        }
    }

    found.website?.let { website ->
        WebsiteScopePage(
            website = website,
            busy = busy,
            alreadySubscribed = website.options.first().feed.url in subscribedUrls,
            error = error,
            onChangeUrl = { searchedUrl = null; found = FeedDiscovery(); error = null },
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
                onValueChange = { url = it; searchedUrl = null; error = null; found = FeedDiscovery() },
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
