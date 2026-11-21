package io.github.sasakiy84.qarry

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun WebsiteScopePage(
    website: WebsiteScopes,
    busy: Boolean,
    alreadySubscribed: Boolean,
    error: String?,
    onChangeUrl: () -> Unit,
    onRegister: (FeedDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedIndex by rememberSaveable(website.options.first().feed.url) { mutableIntStateOf(0) }
    val selected = website.options[selectedIndex.coerceIn(website.options.indices)]
    BackHandler { if (!busy) onChangeUrl() }
    Column(modifier) {
        WebsitePreview(website.html, selected.highlightIds, Modifier.fillMaxWidth().weight(1f))
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().weight(1f)) {
            Text("登録する範囲", Modifier.padding(start = 20.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(website.options.indices.toList()) { index ->
                    val option = website.options[index]
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        enabled = !busy,
                        label = {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    option.label, Modifier.weight(1f, fill = false),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text("${option.feed.articles.size}件", maxLines = 1)
                            }
                        },
                        modifier = Modifier.widthIn(max = 235.dp),
                    )
                }
            }
            key(selectedIndex) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp)) {
                    items(selected.feed.articles, key = { it.key }) { article ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                article.title ?: article.url.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
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
            error?.let {
                Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onChangeUrl, enabled = !busy) { Text("URLを変更") }
                Button(
                    onClick = { onRegister(selected.feed) },
                    enabled = !busy,
                ) {
                    Text(if (alreadySubscribed) "更新範囲を変更" else "この範囲を登録")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebsitePreview(html: String, highlightIds: List<Int>, modifier: Modifier) {
    var ready by remember(html) { mutableStateOf(false) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
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
        modifier = modifier,
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
