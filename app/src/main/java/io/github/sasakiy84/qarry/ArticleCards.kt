package io.github.sasakiy84.qarry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal fun LazyListScope.articleCards(
    articles: List<Article>,
    onArticle: (Article) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    history: Boolean = false,
) {
    items(articles, key = { it.id }) { article ->
        ArticleCard(article, history, { onArticle(article) }, onDismiss)
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun ArticleCard(
    article: Article,
    history: Boolean,
    onOpen: () -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
) {
    if (article.dismissed) {
        ArticleCardContent(article, history, onOpen, null)
        return
    }

    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.42f })
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    fun dismiss(rating: ArticleRating?) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                if (!onDismiss(article, rating)) swipe.reset()
            } finally {
                saving = false
            }
        }
    }

    SwipeToDismissBox(
        state = swipe,
        gesturesEnabled = !saving,
        backgroundContent = {
            val direction = swipe.dismissDirection
            if (direction != SwipeToDismissBoxValue.Settled) {
                val good = direction == SwipeToDismissBoxValue.StartToEnd
                Box(
                    Modifier.fillMaxSize()
                        .background(if (good) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 24.dp)
                        .clearAndSetSemantics { },
                    contentAlignment = if (good) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Text(
                        if (good) ArticleRating.Good.label else ArticleRating.Bad.label,
                        color = if (good) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> dismiss(ArticleRating.Good)
                SwipeToDismissBoxValue.EndToStart -> dismiss(ArticleRating.Bad)
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
    ) {
        ArticleCardContent(
            article,
            history,
            onOpen,
            onRemove = { dismiss(null) },
            onRate = if (!saving) ({ rating -> dismiss(rating) }) else null,
        )
    }
}

@Composable
private fun ArticleCardContent(
    article: Article,
    history: Boolean,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?,
    onRate: ((ArticleRating) -> Unit)? = null,
) {
    val typography = MaterialTheme.typography
    val height = with(LocalDensity.current) {
        typography.labelMedium.lineHeight.toDp() + typography.titleMedium.lineHeight.toDp() +
            typography.bodySmall.lineHeight.toDp() * 2 + typography.bodySmall.fontSize.toDp()
    } + 32.dp
    Row(
        Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                if (onRate != null) customActions = ArticleRating.entries.map { rating ->
                    CustomAccessibilityAction(rating.label) {
                        onRate(rating)
                        true
                    }
                }
            }
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).align(Alignment.Top),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    article.feedTitle, Modifier.weight(1f),
                    style = typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val status = when {
                    article.rating != null -> article.rating.label
                    history -> article.viewedAt?.let(::entryDate)
                    !article.read -> "未読"
                    else -> article.published?.let(::entryDate)
                }
                status?.let {
                    Text(
                        it, style = typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                article.displayTitle, style = typography.titleMedium,
                fontWeight = if (article.read) FontWeight.Medium else FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            article.body?.let { description ->
                Text(
                    description.trim(), style = typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ArticleThumbnail(article.url, article.thumbnailUrl, Modifier.size(width = 76.dp, height = 60.dp))
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = "すべてのキューから削除")
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}
