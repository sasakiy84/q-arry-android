package io.github.sasakiy84.qarry

import android.annotation.SuppressLint
import android.database.sqlite.SQLiteException
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

@Composable
internal fun EditWebsiteScopePage(
    library: FeedLibrary,
    feed: Subscription,
    saving: Boolean,
    onSavingChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var preview by remember { mutableStateOf<WebsiteScopes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(feed.url, attempt) {
        error = null
        try {
            preview = library.inspectWebsite(feed.url)
        } catch (failure: FeedRequestException) {
            error = failure.message
        }
    }
    val website = preview
    val message = error
    when {
        website != null -> WebsiteScopePage(
            website = website,
            busy = saving,
            alreadySubscribed = true,
            error = message,
            onChangeUrl = onCancel,
            onRegister = { chosen ->
                if (!saving) {
                    onSavingChange(true)
                    error = null
                    scope.launch {
                        try {
                            if (library.updateWebsiteScope(feed.id, chosen)) onSaved()
                            else error = "フィードが見つかりませんでした。"
                        } catch (_: SQLiteException) {
                            error = "更新範囲を保存できませんでした。"
                        } finally {
                            onSavingChange(false)
                        }
                    }
                }
            },
            modifier = modifier,
            backLabel = "キャンセル",
        )
        message != null -> Box(modifier, contentAlignment = Alignment.Center) {
            EmptyState(message, null, action = { attempt++ }, actionLabel = "もう一度探す")
        }
        else -> Box(modifier, contentAlignment = Alignment.Center) { LoadingIndicator("監視範囲を探しています") }
    }
}

@Composable
internal fun WebsiteScopePage(
    website: WebsiteScopes,
    busy: Boolean,
    alreadySubscribed: Boolean,
    error: String?,
    onChangeUrl: () -> Unit,
    onRegister: (FeedDocument) -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "URLを変更",
) {
    var selection by rememberSaveable(website.options.first().feed.url) { mutableStateOf<String?>(null) }
    val matchingIndex = website.options.indexOfFirst { it.selectionKey == selection }
    val missingSelection = selection != null && matchingIndex < 0
    val selectedIndex = matchingIndex.coerceAtLeast(0)
    val selected = website.options[selectedIndex]
    val watch = remember(selected.feed.htmlWatch) { selected.feed.htmlWatch?.let(::decodeWebsiteWatch) }
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val optionsState = rememberLazyListState()
    val articlesState = key(selected.selectionKey) { rememberLazyListState() }
    BackHandler { if (!busy) onChangeUrl() }
    val controls: @Composable (Modifier) -> Unit = { panel ->
        Column(panel) {
            Text(
                "登録する範囲",
                Modifier.padding(start = 20.dp, top = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            LazyRow(
                state = optionsState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(website.options.indices.toList()) { index ->
                    val option = website.options[index]
                    FilterChip(
                        selected = !missingSelection && index == selectedIndex,
                        onClick = { selection = option.selectionKey },
                        enabled = !busy,
                        label = {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    option.label, Modifier.weight(1f, fill = false),
                                    maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text("${option.feed.articles.size}件", maxLines = 1)
                            }
                        },
                        modifier = Modifier.widthIn(max = 235.dp),
                    )
                }
            }
            key(selectedIndex) {
                LazyColumn(Modifier.weight(1f), state = articlesState, contentPadding = PaddingValues(horizontal = 20.dp)) {
                    selected.feed.htmlItemSelector?.let { selector ->
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "クエリパス（CSSセレクター）", Modifier.semantics { heading() },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                if (watch?.table != null) watch.heading?.let { heading ->
                                    Text("見出し「${heading.text}」から表を特定", style = MaterialTheme.typography.bodySmall)
                                }
                                SelectionContainer {
                                    Text(
                                        selector,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    items(selected.feed.articles, key = { it.key }) { article ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                article.title ?: article.url.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            articleTimestampLabel(article.updatedAt, article.publishedTime, discoveredAt = selected.feed.observedAt)
                                ?.let { date -> Text(date, style = MaterialTheme.typography.labelSmall) }
                            article.body?.let { description ->
                                Text(
                                    description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            (error ?: "前に選んだ範囲が見つかりません。範囲を選び直してください。".takeIf { missingSelection })?.let {
                Text(it, Modifier.padding(horizontal = 20.dp).announceStatus(), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onChangeUrl, enabled = !busy) { Text(backLabel) }
                Button(
                    onClick = { onRegister(selected.feed) },
                    enabled = !busy && !missingSelection,
                ) {
                    Text(if (alreadySubscribed) "更新範囲を変更" else "この範囲を登録")
                }
            }
        }
    }
    BoxWithConstraints(modifier) {
        if (maxHeight < 440.dp && maxWidth >= 560.dp) {
            Row(Modifier.fillMaxSize()) {
                WebsitePreview(website.html, selected.highlightIds, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                controls(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            val previewWeight = when {
                maxHeight < 440.dp -> 0.35f
                largeText -> 0.5f
                else -> 1f
            }
            Column(Modifier.fillMaxSize()) {
                WebsitePreview(website.html, selected.highlightIds, Modifier.fillMaxWidth().weight(previewWeight))
                HorizontalDivider()
                controls(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

private val WebsiteScope.selectionKey: String get() = "$label\u0000${feed.htmlItemSelector}"

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebsitePreview(html: String, highlightIds: List<Int>, modifier: Modifier) {
    var ready by remember(html) { mutableStateOf(false) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // The native list below exposes the selected articles in reading order.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isFocusableInTouchMode = false
                isFocusable = false
                settings.apply {
                    // The preview contains sanitized, local HTML. JavaScript only updates the selection.
                    javaScriptEnabled = true
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun onPageFinished(view: WebView, url: String?) {
                        ready = true
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier.semantics { hideFromAccessibility() },
        onRelease = { it.destroy() },
        update = { view ->
            if (ready && view.tag != highlightIds) {
                view.tag = highlightIds
                view.evaluateJavascript(
                    """
                    (() => {
                      const ids = new Set(${highlightIds.joinToString(prefix = "[", postfix = "]")});
                      document.querySelectorAll('[$SCOPE_MARKER]').forEach(item => {
                        item.classList.toggle('qarry-selected', ids.has(Number(item.getAttribute('$SCOPE_MARKER'))));
                      });
                      document.querySelector('.qarry-selected')?.scrollIntoView({block: 'start'});
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        },
    )
}
