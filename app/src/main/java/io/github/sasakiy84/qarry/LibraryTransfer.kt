package io.github.sasakiy84.qarry

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import java.io.IOException

internal enum class LibraryTransfer { ImportingFeeds, ExportingFeeds, ExportingRatings }

internal data class LibraryTransferActions(
    val transfer: LibraryTransfer?,
    val choosingFile: Boolean,
    val onImport: () -> Unit,
    val onExport: () -> Unit,
    val onExportRatings: () -> Unit,
)

@Composable
internal fun rememberLibraryTransfer(
    library: FeedLibrary,
    snackbars: SnackbarHostState,
    change: (suspend () -> Unit) -> Unit,
): LibraryTransferActions {
    val context = LocalContext.current
    var choosingFile by rememberSaveable { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<LibraryTransfer?>(null) }

    fun runTransfer(kind: LibraryTransfer, failureMessage: String, operation: suspend () -> String) {
        transfer = kind
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
                transfer = null
            }
            snackbars.showSnackbar(message)
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        choosingFile = false
        if (uri != null) {
            runTransfer(LibraryTransfer.ImportingFeeds, "OPMLファイルを開けませんでした。") {
                val imported = library.importOpml { context.contentResolver.openInputStream(uri) }
                if (imported.feedIds.isNotEmpty()) change { library.refreshImported(imported.feedIds) }
                buildList {
                    add(if (imported.feedIds.isEmpty()) "新しく登録するフィードはありません" else "${imported.feedIds.size}件を登録しました")
                    if (imported.createdQueues > 0) add("キュー${imported.createdQueues}件を復元")
                    if (imported.duplicateCount > 0) add("重複${imported.duplicateCount}件")
                    if (imported.unsupportedCount > 0) add("対象外${imported.unsupportedCount}件")
                }.joinToString("・")
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        choosingFile = false
        if (uri != null) {
            runTransfer(LibraryTransfer.ExportingFeeds, "OPMLファイルを保存できませんでした。") {
                val exported = library.exportOpml { context.contentResolver.openOutputStream(uri, "wt") }
                "${exported}件のフィードをエクスポートしました。"
            }
        }
    }

    val ratingsPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        choosingFile = false
        if (uri != null) {
            runTransfer(LibraryTransfer.ExportingRatings, "評価履歴を保存できませんでした。") {
                val exported = library.exportRatingHistory { context.contentResolver.openOutputStream(uri, "wt") }
                "${exported}件の評価をエクスポートしました。"
            }
        }
    }

    fun chooseFile(launchPicker: () -> Unit) {
        if (choosingFile || transfer != null) return
        choosingFile = true
        try {
            launchPicker()
        } catch (_: ActivityNotFoundException) {
            choosingFile = false
            change { snackbars.showSnackbar("ファイルを選択できるアプリがありません。") }
        }
    }

    return LibraryTransferActions(
        transfer = transfer,
        choosingFile = choosingFile,
        onImport = { chooseFile { importPicker.launch(arrayOf("*/*")) } },
        onExport = { chooseFile { exportPicker.launch("q-arry-feeds.opml") } },
        onExportRatings = { chooseFile { ratingsPicker.launch("q-arry-ratings.json") } },
    )
}
