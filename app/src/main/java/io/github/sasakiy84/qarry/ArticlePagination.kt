package io.github.sasakiy84.qarry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first

private const val ARTICLE_PREFETCH_DISTANCE = 5

@Composable
internal fun LoadNextArticlePage(
    listState: LazyListState,
    itemCount: Int,
    enabled: Boolean,
    onLoad: () -> Unit,
) {
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(listState, itemCount, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            // Wait for the new page to be laid out before checking what is left.
            layout.totalItemsCount == itemCount && lastVisible != null &&
                itemCount - lastVisible - 1 <= ARTICLE_PREFETCH_DISTANCE
        }.first { it }
        load()
    }
}

internal fun LazyListScope.articlePageFooter(loading: Boolean, onRetry: (() -> Unit)? = null) {
    if (!loading && onRetry == null) return
    item(key = "article-page-footer") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (onRetry != null) {
                TextButton(onRetry) { Text("追加の読み込みを再試行") }
            } else {
                LoadingIndicator("次の記事を読み込み中")
            }
        }
    }
}
