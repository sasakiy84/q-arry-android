package io.github.sasakiy84.qarry

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
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
    showFeedTitle: Boolean = true,
) {
    items(articles, key = { it.id }) { article ->
        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            ArticleCard(article, history, showFeedTitle, { press -> onArticle(article, press) }, onDismiss)
        }
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

private val articleCardShape = RoundedCornerShape(12.dp)

private enum class ArticleSwipe(val label: String, val rating: ArticleRating?) {
    Remove("削除", null),
    Bad(ArticleRating.Bad.label, ArticleRating.Bad),
    Good(ArticleRating.Good.label, ArticleRating.Good),
}

@Composable
private fun ArticleCard(
    article: Article,
    history: Boolean,
    showFeedTitle: Boolean,
    onOpen: (ArticlePress) -> Unit,
    onDismiss: suspend (Article, ArticleRating?) -> Boolean,
) {
    if (article.dismissed) {
        ArticleCardContent(article, history, showFeedTitle, onOpen, modifier = Modifier.clip(articleCardShape))
        return
    }

    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var resetAnimation by remember { mutableStateOf<Job?>(null) }
    val (firstThreshold, secondThreshold) = with(LocalDensity.current) {
        minOf(width * 0.28f, 104.dp.toPx()) to minOf(width * 0.46f, 168.dp.toPx())
    }

    fun swipeAction(displacement: Float): ArticleSwipe = when {
        displacement > 0f -> ArticleSwipe.Remove
        displacement <= -secondThreshold -> ArticleSwipe.Good
        else -> ArticleSwipe.Bad
    }

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
        Modifier.fillMaxWidth().clip(articleCardShape).onSizeChanged { width = it.width }
            .pointerInput(width, firstThreshold, secondThreshold, saving) {
                if (saving || width <= 0) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var swiping = false
                    while (true) {
                        val change = awaitPointerEvent().changes.singleOrNull() ?: break
                        if (change.id != down.id || change.isConsumed) break
                        val displacement = change.position - down.position
                        if (!change.pressed) {
                            if (swiping && abs(displacement.x) >= firstThreshold) {
                                change.consume()
                                dismissLatest(swipeAction(displacement.x).rating)
                                return@awaitEachGesture
                            }
                            break
                        }
                        if (!swiping) {
                            val horizontal = abs(displacement.x)
                            val vertical = abs(displacement.y)
                            // Let the list take over as soon as a diagonal gesture reaches the vertical touch slop.
                            if (horizontal <= vertical * 1.5f) {
                                if (vertical >= touchSlop) break
                                continue
                            }
                            if (horizontal < touchSlop * 1.5f) continue
                            resetAnimation?.cancel()
                            swiping = true
                        }
                        offset = displacement.x.coerceIn(-width.toFloat(), width.toFloat())
                        change.consume()
                    }
                    if (swiping) resetPosition()
                }
            },
    ) {
        if (offset != 0f) {
            val action = swipeAction(offset)
            val ready = abs(offset) >= firstThreshold
            val colors = MaterialTheme.colorScheme
            val (background, textColor) = when (action) {
                ArticleSwipe.Remove -> if (ready) colors.secondary to colors.onSecondary
                    else colors.secondaryContainer to colors.onSecondaryContainer
                ArticleSwipe.Bad -> if (ready) colors.error to colors.onError
                    else colors.errorContainer to colors.onErrorContainer
                ArticleSwipe.Good -> colors.primary to colors.onPrimary
            }
            Box(
                Modifier.matchParentSize().background(background).padding(horizontal = 16.dp).clearAndSetSemantics { },
                contentAlignment = if (action == ArticleSwipe.Remove) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight,
            ) {
                Text(
                    if (ready) "${action.label} ✓" else action.label,
                    color = textColor, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
            }
        }
        ArticleCardContent(
            article,
            history,
            showFeedTitle,
            onOpen,
            onDismiss = if (saving) null else dismissLatest,
            modifier = Modifier.absoluteOffset { IntOffset(offset.roundToInt(), 0) },
        )
    }
}

@Composable
private fun ArticleCardContent(
    article: Article,
    history: Boolean,
    showFeedTitle: Boolean,
    onOpen: (ArticlePress) -> Unit,
    onDismiss: ((ArticleRating?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val metadata = rememberArticleMetadata(article.url)
    val image = rememberArticleImage(metadata?.imageUrl, article.thumbnailUrl)
    val typography = MaterialTheme.typography
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val title = metadata?.title ?: article.displayTitle
    val titleLines = if (largeText) 2 else 1
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .articleOpenActions(article.url, onOpen)
            .semantics {
                if (onDismiss != null) customActions = ArticleSwipe.entries.map { action ->
                    val label = action.rating?.let { "${it.label} と評価してすべてのキューから削除" }
                        ?: "すべてのキューから削除"
                    CustomAccessibilityAction(label) {
                        onDismiss(action.rating)
                        true
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            title, style = typography.titleSmall.copy(lineBreak = LineBreak.Heading),
            fontWeight = if (article.read) FontWeight.Medium else FontWeight.SemiBold,
            minLines = titleLines, maxLines = titleLines, overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val date = article.timestampLabel(metadata)
                if (largeText) {
                    Text(
                        date.orEmpty(), style = typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!largeText && date != null) {
                        Text(date, style = typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (showFeedTitle) {
                        Text(
                            article.feedTitle, Modifier.weight(1f),
                            style = typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                    (metadata?.description ?: article.body).orEmpty().trim(),
                    style = typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            ArticleThumbnail(image, Modifier.width(if (largeText) 80.dp else 96.dp).aspectRatio(16f / 9f))
        }
    }
}
