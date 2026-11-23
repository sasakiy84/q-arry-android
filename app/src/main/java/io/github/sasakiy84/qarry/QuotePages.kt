package io.github.sasakiy84.qarry

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.IOException

internal const val QUOTE_TRAINING_TITLE = "引用の好みを調整"
private const val QUOTE_LINES = 3
private val quoteInset = 16.dp

@Composable
internal fun QuoteSettings(enabled: Boolean, onEnabled: (Boolean) -> Unit, onTrain: () -> Unit) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(
            "引用", Modifier.padding(horizontal = 20.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        ListItem(
            headlineContent = { Text("引用を表示") },
            trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
            modifier = Modifier.toggleable(enabled, role = Role.Switch, onValueChange = onEnabled),
        )
        TextButton(onTrain, Modifier.padding(horizontal = 12.dp)) { Text(QUOTE_TRAINING_TITLE) }
    }
}

@Composable
internal fun ArticleLeadQuote(learning: QuoteLearning, source: QuoteSource, modifier: Modifier = Modifier) {
    val revision by learning.revision.collectAsState()
    var ranked by remember(source, revision) { mutableStateOf(emptyList<QuoteSpan>()) }
    LaunchedEffect(learning, source, revision) {
        ranked = try {
            learning.forArticle(source)
        } catch (error: IOException) {
            Log.w("QarryQuotes", "Quote inference unavailable", error)
            emptyList()
        } catch (_: SQLiteException) {
            emptyList()
        }
    }
    if (ranked.isEmpty()) return
    val style = MaterialTheme.typography.bodyLarge
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fits = rememberQuoteFit(style, constraints.maxWidth)
        val quote = ranked.firstOrNull { fits(it.text) }
        if (quote != null) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                QuoteText(quote.text, style)
            }
        }
    }
}

@Composable
internal fun QuoteTrainingPage(learning: QuoteLearning, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    var lastScanned by rememberSaveable { mutableStateOf<Long?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var comparison by remember { mutableStateOf<QuoteComparison?>(null) }
    var status by remember { mutableStateOf<QuoteLearningState?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(comparison?.id) { scroll.scrollTo(0) }
    BoxWithConstraints(modifier.padding(horizontal = 16.dp)) {
        val fits = rememberQuoteFit(style, constraints.maxWidth)
        LaunchedEffect(learning, generation, fits) {
            loading = true
            failure = null
            try {
                val state = learning.state()
                status = state
                // A change in display size keeps the presented order when both excerpts still fit.
                if (comparison?.displayed?.all { fits(it.span.text) } == true) return@LaunchedEffect
                var before = lastScanned
                comparison = null
                while (true) {
                    val source = learning.nextSource(before) ?: break
                    val ranked = learning.rank(source, minimum = 2)
                    comparison = ranked.compare(ranked.candidates.filter { fits(it.span.text) }, state.weights)
                    if (comparison != null) break
                    before = source.articleId
                }
            } catch (error: IOException) {
                Log.w("QarryQuotes", "Quote training candidates unavailable", error)
                failure = "引用候補を準備できませんでした"
            } catch (_: SQLiteException) {
                failure = "引用の学習データを読み込めませんでした"
            } finally {
                loading = false
            }
        }

        fun submit(shown: QuoteComparison, choice: Int?) {
            if (saving || loading || comparison?.id != shown.id) return
            saving = true
            scope.launch {
                try {
                    learning.record(shown, choice)
                    loading = true
                    lastScanned = shown.articleId
                    comparison = null
                    generation++
                    delay(250)
                } catch (_: SQLiteException) {
                    failure = "回答を保存できませんでした"
                } finally {
                    saving = false
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            status?.let { state ->
                Text(
                    "選択済み ${state.answers}件", Modifier.announceStatus(),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val shown = comparison?.takeIf { it.displayed.all { candidate -> fits(candidate.span.text) } }
            when {
                loading && shown == null -> LoadingIndicator("引用候補を読み込み中")
                shown != null -> {
                    Text(shown.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text("続きを読みたくなるのは？", style = MaterialTheme.typography.bodyMedium)
                    for ((position, candidate) in shown.displayed.withIndex()) {
                        Card(
                            onClick = { submit(shown, position) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !saving && !loading,
                        ) {
                            QuoteText(candidate.span.text, style)
                        }
                    }
                    TextButton({ submit(shown, null) }, enabled = !saving && !loading) { Text("スキップ") }
                }
                failure == null -> Text("比較できる保存済みの記事がありません")
            }
            failure?.let { message ->
                Text(message, Modifier.announceStatus(), color = MaterialTheme.colorScheme.error)
                if (shown == null) TextButton({ lastScanned = null; generation++ }) { Text("再試行") }
            }
            status?.lastAnswerId?.let { answerId ->
                TextButton(
                    enabled = !saving && !loading,
                    onClick = {
                        if (!saving && !loading) {
                            saving = true
                            scope.launch {
                                try {
                                    learning.undo(answerId)
                                    loading = true
                                    lastScanned = null
                                    comparison = null
                                    generation++
                                } catch (_: SQLiteException) {
                                    failure = "選択を取り消せませんでした"
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                ) { Text("直前の選択を取り消す") }
            }
        }
    }
}

@Composable
private fun QuoteText(text: String, style: TextStyle) {
    Text(
        text, Modifier.fillMaxWidth().padding(horizontal = quoteInset, vertical = 12.dp),
        style = style, maxLines = QUOTE_LINES,
    )
}

@Composable
private fun rememberQuoteFit(style: TextStyle, cardWidth: Int): (String) -> Boolean {
    val density = LocalDensity.current
    val width = with(density) { (cardWidth - 2 * quoteInset.roundToPx()).coerceAtLeast(0) }
    val measurer = rememberTextMeasurer(cacheSize = 40)
    return remember(measurer, width, density, style) {
        { text ->
            width > 0 && !measurer.measure(
                AnnotatedString(text), style, maxLines = QUOTE_LINES, constraints = Constraints(maxWidth = width),
            ).hasVisualOverflow
        }
    }
}
