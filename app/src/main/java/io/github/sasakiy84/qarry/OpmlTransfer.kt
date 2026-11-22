package io.github.sasakiy84.qarry

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import java.io.IOException

internal enum class OpmlTransfer { Importing, Exporting }

internal data class OpmlTransferActions(
    val transfer: OpmlTransfer?,
    val choosingFile: Boolean,
    val onImport: () -> Unit,
    val onExport: () -> Unit,
)

@Composable
internal fun rememberOpmlTransfer(
    library: FeedLibrary,
    snackbars: SnackbarHostState,
    change: (suspend () -> Unit) -> Unit,
): OpmlTransferActions {
    val context = LocalContext.current
    var choosingOpml by rememberSaveable { mutableStateOf(false) }
    var opmlTransfer by remember { mutableStateOf<OpmlTransfer?>(null) }

    fun runTransfer(kind: OpmlTransfer, failureMessage: String, operation: suspend () -> String) {
        opmlTransfer = kind
        change {
            val message = try {
                operation()
            } catch (error: OpmlFormatException) {
                error.message.orEmpty()
            } catch (_: IOException) {
                failureMessage
            } catch (_: SecurityException) {
                failureMessage
            } finally {
                opmlTransfer = null
            }
            snackbars.showSnackbar(message)
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        choosingOpml = false
        if (uri != null) {
            runTransfer(OpmlTransfer.Importing, "OPMLファイルを開けませんでした。") {
                val imported = library.importOpml { context.contentResolver.openInputStream(uri) }
                if (imported.feedIds.isNotEmpty()) change { library.refreshImported(imported.feedIds) }
                buildList {
                    add(if (imported.feedIds.isEmpty()) "新しく登録するフィードはありません" else "${imported.feedIds.size}件を登録しました")
                    if (imported.duplicateCount > 0) add("重複${imported.duplicateCount}件")
                    if (imported.unsupportedCount > 0) add("対象外${imported.unsupportedCount}件")
                }.joinToString("・")
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        choosingOpml = false
        if (uri != null) {
            runTransfer(OpmlTransfer.Exporting, "OPMLファイルを保存できませんでした。") {
                val exported = library.exportOpml { context.contentResolver.openOutputStream(uri, "wt") }
                "${exported}件のフィードをエクスポートしました。"
            }
        }
    }

    fun chooseOpml(launchPicker: () -> Unit) {
        if (choosingOpml || opmlTransfer != null) return
        choosingOpml = true
        try {
            launchPicker()
        } catch (_: ActivityNotFoundException) {
            choosingOpml = false
            change { snackbars.showSnackbar("ファイルを選択できるアプリがありません。") }
        }
    }

    return OpmlTransferActions(
        transfer = opmlTransfer,
        choosingFile = choosingOpml,
        onImport = { chooseOpml { importPicker.launch(arrayOf("*/*")) } },
        onExport = { chooseOpml { exportPicker.launch("q-arry-feeds.opml") } },
    )
}
