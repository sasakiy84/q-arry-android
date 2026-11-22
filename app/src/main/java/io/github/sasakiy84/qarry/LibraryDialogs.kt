package io.github.sasakiy84.qarry

import android.database.sqlite.SQLiteException
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

internal sealed interface DeleteTarget {
    data class Feed(val id: Long) : DeleteTarget
    data class Queue(val id: Long) : DeleteTarget
}

internal class LibraryDialogState(
    queueEditorState: MutableState<QueueEditor?>,
    managedFeedState: MutableState<Long?>,
    filteredFeedState: MutableState<Long?>,
) {
    var queueEditor by queueEditorState
    var managedFeedId by managedFeedState
    var filteredFeedId by filteredFeedState
    var confirmDelete by mutableStateOf<DeleteTarget?>(null)
}

private val queueEditorSaver = listSaver<QueueEditor?, String>(
    save = { editor -> editor?.let { listOf(it.id?.toString().orEmpty(), it.name) }.orEmpty() },
    restore = { values -> if (values.size == 2) QueueEditor(values[0].toLongOrNull(), values[1]) else null },
)

@Composable
internal fun rememberLibraryDialogState(): LibraryDialogState {
    val queueEditor = rememberSaveable(stateSaver = queueEditorSaver) { mutableStateOf<QueueEditor?>(null) }
    val managedFeed = rememberSaveable { mutableStateOf<Long?>(null) }
    val filteredFeed = rememberSaveable { mutableStateOf<Long?>(null) }
    return remember(queueEditor, managedFeed, filteredFeed) { LibraryDialogState(queueEditor, managedFeed, filteredFeed) }
}

@Composable
internal fun LibraryDialogs(
    state: LibraryDialogState,
    snapshot: LibrarySnapshot?,
    listStates: SaveableStateHolder,
    library: FeedLibrary,
    change: (suspend () -> Unit) -> Unit,
    onQueueRemoved: (Long) -> Unit,
) {
    state.queueEditor?.let { editor ->
        QueueNameDialog(editor, onDismiss = { state.queueEditor = null }) { name ->
            try {
                if (library.nameQueue(editor.id, name)) {
                    state.queueEditor = null
                    null
                } else {
                    "同じ名前のキューがあるか、キューが削除されています。"
                }
            } catch (_: SQLiteException) {
                "キューを保存できませんでした。"
            }
        }
    }
    snapshot?.subscriptions?.firstOrNull { it.id == state.managedFeedId }?.takeIf { state.queueEditor == null }?.let { feed ->
        val key = "membership-${feed.id}"
        fun close() {
            listStates.removeState(key)
            state.managedFeedId = null
        }
        listStates.SaveableStateProvider(key) {
            QueueMembershipDialog(
                feed = feed,
                queues = snapshot?.queues.orEmpty(),
                onDismiss = ::close,
                onCreateQueue = { state.queueEditor = QueueEditor(null, "") },
                onSave = { selected, filters ->
                    try {
                        library.setMembership(feed.id, selected, filters)
                        close()
                        null
                    } catch (_: SQLiteException) {
                        "キュー設定を保存できませんでした。"
                    }
                },
            )
        }
    }
    snapshot?.subscriptions?.firstOrNull { it.id == state.filteredFeedId }?.let { feed ->
        KeywordFilterDialog(
            scopeLabel = "フィード全体",
            initial = feed.keywordFilter,
            replaceMessage = if (feed.queueKeywordFilters.isNotEmpty()) "保存すると、このフィードのキュー別フィルターを解除します。" else null,
            onDismiss = { state.filteredFeedId = null },
            onSave = { rule ->
                try {
                    library.setFeedKeywordFilter(feed.id, rule)
                    state.filteredFeedId = null
                    null
                } catch (_: SQLiteException) {
                    "フィルターを保存できませんでした。"
                }
            },
        )
    }
    state.confirmDelete?.let { target ->
        val isFeed = target is DeleteTarget.Feed
        AlertDialog(
            onDismissRequest = { state.confirmDelete = null },
            title = { Text(if (isFeed) "フィードを解除しますか？" else "キューを削除しますか？") },
            text = { Text(if (isFeed) "このフィードの記事とキューへの登録が端末から削除されます。" else "フィードと記事は購読一覧に残ります。") },
            confirmButton = {
                TextButton(onClick = {
                    state.confirmDelete = null
                    change {
                        when (target) {
                            is DeleteTarget.Feed -> {
                                library.removeFeed(target.id)
                            }
                            is DeleteTarget.Queue -> {
                                library.removeQueue(target.id)
                                onQueueRemoved(target.id)
                            }
                        }
                    }
                }) { Text(if (isFeed) "解除" else "削除") }
            },
            dismissButton = { TextButton(onClick = { state.confirmDelete = null }) { Text("キャンセル") } },
        )
    }
}
