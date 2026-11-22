package io.github.sasakiy84.qarry

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun LazyListScope.articleCards(
    articles: List<Article>,
    onArticle: (Article, ArticlePress) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
    history: Boolean = false,
) {
    items(articles, key = { it.id }) { article ->
        ArticleCard(article, history, { press -> onArticle(article, press) }, onDismiss)
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
internal fun Modifier.articleOpenActions(
    url: String?, onOpen: (ArticlePress) -> Unit, hasArticleDetail: Boolean = true,
): Modifier {
    val hasUrl = url?.let(::webUrl) != null
    val tap = when {
        !hasArticleDetail -> ArticleOpenMode.Browser
        !hasUrl -> ArticleOpenMode.InApp
        else -> LocalArticleOpenMode.current
    }
    val canOpenEitherWay = hasArticleDetail && hasUrl
    return combinedClickable(
        role = Role.Button,
        onClickLabel = tap.actionLabel,
        onClick = { onOpen(ArticlePress.Tap) },
        onLongClickLabel = tap.forPress(ArticlePress.LongPress).actionLabel.takeIf { canOpenEitherWay },
        onLongClick = if (canOpenEitherWay) ({ onOpen(ArticlePress.LongPress) }) else null,
    )
}

@Composable
private fun ArticleCard(
    article: Article,
    history: Boolean,
    onOpen: (ArticlePress) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
) {
    if (article.dismissed) {
        ArticleCardContent(article, history, onOpen, null)
        return
    }

    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var resetAnimation by remember { mutableStateOf<Job?>(null) }
    val threshold = minOf(width * 0.45f, with(LocalDensity.current) { 192.dp.toPx() })

    fun resetPosition() {
        resetAnimation?.cancel()
        if (offset != 0f) {
            resetAnimation = scope.launch {
                animate(offset, 0f, animationSpec = tween(160)) { value, _ -> offset = value }
            }
        }
    }

    fun dismiss(rating: ArticleRating?) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                if (!onDismiss(article, rating)) resetPosition()
            } finally {
                saving = false
            }
        }
    }

    val dismissLatest by rememberUpdatedState(::dismiss)
    Box(
        Modifier.fillMaxWidth().clipToBounds().onSizeChanged { width = it.width }
            .pointerInput(width, threshold, saving) {
                if (saving) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        resetAnimation?.cancel()
                        offset = 0f
                    },
                    onHorizontalDrag = { _, delta -> offset = (offset + delta).coerceIn(-width.toFloat(), width.toFloat()) },
                    onDragCancel = ::resetPosition,
                    onDragEnd = {
                        if (threshold > 0f && abs(offset) >= threshold) {
                            dismissLatest(if (offset > 0f) ArticleRating.Good else ArticleRating.Bad)
                        } else {
                            resetPosition()
                        }
                    },
                )
            },
    ) {
        if (offset != 0f) {
            val good = offset > 0f
            val ready = abs(offset) >= threshold
            val colors = MaterialTheme.colorScheme
            val background = when {
                ready && good -> colors.primary
                ready -> colors.error
                good -> colors.primaryContainer
                else -> colors.errorContainer
            }
            val textColor = when {
                ready && good -> colors.onPrimary
                ready -> colors.onError
                good -> colors.onPrimaryContainer
                else -> colors.onErrorContainer
            }
            Box(
                Modifier.matchParentSize().background(background).padding(horizontal = 24.dp).clearAndSetSemantics { },
                contentAlignment = if (good) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight,
            ) {
                val label = if (good) ArticleRating.Good.label else ArticleRating.Bad.label
                Text(
                    if (ready) "$label ✓" else label,
                    color = textColor, style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        ArticleCardContent(
            article,
            history,
            onOpen,
            onRemove = { dismiss(null) },
            onRate = if (!saving) ({ rating -> dismiss(rating) }) else null,
            modifier = Modifier.absoluteOffset { IntOffset(offset.roundToInt(), 0) },
        )
    }
}

@Composable
private fun ArticleCardContent(
    article: Article,
    history: Boolean,
    onOpen: (ArticlePress) -> Unit,
    onRemove: (() -> Unit)?,
    onRate: ((ArticleRating) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val metadata = rememberArticleMetadata(article.url)
    val image = rememberArticleImage(metadata?.imageUrl, article.thumbnailUrl)
    val typography = MaterialTheme.typography
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val title = metadata?.title ?: article.displayTitle
    val titleLines = if (largeText) 2 else 1
    val height = with(LocalDensity.current) {
        typography.labelMedium.lineHeight.toDp() + typography.labelSmall.lineHeight.toDp() +
            typography.titleMedium.lineHeight.toDp() * titleLines +
            typography.bodySmall.lineHeight.toDp() * 2 + typography.bodySmall.fontSize.toDp()
    } + 36.dp
    Row(
        modifier.fillMaxWidth().heightIn(min = height).background(MaterialTheme.colorScheme.surface)
            .articleOpenActions(article.url, onOpen)
            .semantics {
                if (onRate != null) customActions = ArticleRating.entries.map { rating ->
                    CustomAccessibilityAction("${rating.label} と評価してすべてのキューから外す") {
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
                    else -> null
                }
                status?.let {
                    Text(
                        it, style = typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                title, style = typography.titleMedium,
                fontWeight = if (article.read) FontWeight.Medium else FontWeight.SemiBold,
                maxLines = titleLines, overflow = TextOverflow.Ellipsis,
            )
            article.timestampLabel(metadata)?.let { date ->
                Text(date, style = typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            (metadata?.description ?: article.body)?.let { description ->
                Text(
                    description.trim(), style = typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ArticleThumbnail(image, if (largeText) Modifier.size(48.dp) else Modifier.size(width = 76.dp, height = 60.dp))
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = "「$title」をすべてのキューから削除")
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}
