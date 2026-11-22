package io.github.sasakiy84.qarry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class QueueEditor(val id: Long?, val name: String)

@Composable
fun QueueNameDialog(editor: QueueEditor, onDismiss: () -> Unit, onSave: suspend (String) -> String?) {
    var name by rememberSaveable { mutableStateOf(editor.name) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    fun save() {
        if (name.isBlank() || saving) return
        saving = true
        failure = null
        scope.launch {
            try {
                failure = onSave(name.trim())
            } finally {
                saving = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (editor.id == null) "キューを作成" else "キュー名を変更") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80); failure = null },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text("キュー名") },
                    enabled = !saving,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                LaunchedEffect(focus) { focus.requestFocus() }
                failure?.let { Text(it, Modifier.announceStatus(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(::save, enabled = name.isNotBlank() && !saving) { Text("保存") } },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("キャンセル") } },
    )
}

private val selectedQueuesSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() })
private val queueFiltersSaver = listSaver<Map<Long, KeywordFilter>, String>(
    save = { filters -> filters.flatMap { (id, rule) -> listOf(id.toString(), rule.mode.name, rule.keywords.joinToString("\n")) } },
    restore = { values -> values.chunked(3).associate { (id, mode, terms) ->
        id.toLong() to KeywordFilter(KeywordFilterMode.valueOf(mode), terms.lines())
    } },
)

@Composable
internal fun QueueMembershipDialog(
    feed: Subscription,
    queues: List<FeedQueue>,
    onDismiss: () -> Unit,
    onCreateQueue: () -> Unit,
    onSave: suspend (Set<Long>, Map<Long, KeywordFilter>) -> String?,
) {
    var selected by rememberSaveable(feed.id, stateSaver = selectedQueuesSaver) { mutableStateOf(feed.queueIds) }
    var filters by rememberSaveable(feed.id, stateSaver = queueFiltersSaver) { mutableStateOf(feed.queueKeywordFilters) }
    var editingQueueId by rememberSaveable(feed.id) { mutableStateOf<Long?>(null) }
    var saving by remember(feed.id) { mutableStateOf(false) }
    var failure by remember(feed.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val availableSelection = selected.intersect(queues.map { it.id }.toSet())
    val selectedFilters = filters.filterKeys { it in availableSelection }

    queues.firstOrNull { it.id == editingQueueId }?.let { queue ->
        KeywordFilterDialog(
            scopeLabel = queue.name,
            initial = filters[queue.id],
            replaceMessage = if (feed.keywordFilter != null) "キュー設定を保存すると、フィード全体のフィルターを解除します。" else null,
            onDismiss = { editingQueueId = null },
            onSave = { rule ->
                filters = if (rule == null) filters - queue.id else filters + (queue.id to rule)
                editingQueueId = null
                null
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("キューを設定") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Text(feed.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                }
                if (queues.isEmpty()) {
                    item { Text("キューがありません。") }
                } else {
                    item { TextButton(onCreateQueue, enabled = !saving) { Text("キューを作成") } }
                    if (feed.keywordFilter != null && selectedFilters.isNotEmpty()) {
                        item { Text(
                            "保存すると、フィード全体のフィルターを解除します。",
                            Modifier.announceStatus(),
                            style = MaterialTheme.typography.bodySmall,
                        ) }
                    }
                    items(queues, key = { it.id }) { queue ->
                        val checked = queue.id in selected
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f).heightIn(min = 48.dp)
                                    .toggleable(checked, enabled = !saving, role = Role.Checkbox) { value ->
                                        selected = if (value) selected + queue.id else selected - queue.id
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(checked, onCheckedChange = null, enabled = !saving)
                                Text(queue.name)
                            }
                            if (checked) {
                                TextButton(onClick = { editingQueueId = queue.id }, enabled = !saving) {
                                    Text(filters[queue.id]?.summary ?: "フィルター")
                                }
                            }
                        }
                    }
                }
                failure?.let { message ->
                    item { Text(message, Modifier.announceStatus(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (queues.isEmpty()) {
                TextButton(onCreateQueue) { Text("キューを作成") }
            } else {
                TextButton(onClick = {
                    if (saving) return@TextButton
                    saving = true
                    scope.launch {
                        try {
                            failure = onSave(availableSelection, selectedFilters)
                        } finally {
                            saving = false
                        }
                    }
                }, enabled = !saving) { Text("保存") }
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("キャンセル") } },
    )
}
