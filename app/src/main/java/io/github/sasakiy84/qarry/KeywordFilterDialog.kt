package io.github.sasakiy84.qarry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun KeywordFilterDialog(
    scopeLabel: String,
    initial: KeywordFilter?,
    replaceMessage: String?,
    onDismiss: () -> Unit,
    onSave: suspend (KeywordFilter?) -> String?,
) {
    var mode by rememberSaveable { mutableStateOf(initial?.mode) }
    var text by rememberSaveable { mutableStateOf(initial?.keywords.orEmpty().joinToString("\n")) }
    var saving by remember { mutableStateOf(false) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val terms = text.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
    val modes = listOf(null, KeywordFilterMode.Include, KeywordFilterMode.Exclude)

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("キーワードフィルター") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(scopeLabel, style = MaterialTheme.typography.titleSmall)
                if (mode != null && replaceMessage != null) {
                    Text(replaceMessage, Modifier.announceStatus(), style = MaterialTheme.typography.bodySmall)
                }
                KeywordFilterModePicker(modes, mode, enabled = !saving) { mode = it; failure = null }
                mode?.let { selected ->
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; failure = null },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("キーワード") },
                        supportingText = { Text("1行に1キーワード。タイトル・説明に部分一致します。") },
                        minLines = 2,
                        maxLines = 5,
                    )
                    Text(
                        if (selected == KeywordFilterMode.Include) "いずれかに一致する記事だけを表示します。"
                        else "いずれかに一致する記事を除外します。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                failure?.let { Text(it, Modifier.announceStatus(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (saving) return@TextButton
                    val rule = mode?.let { KeywordFilter(it, terms) }
                    saving = true
                    scope.launch {
                        try {
                            failure = onSave(rule)
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving && (mode == null || terms.isNotEmpty()),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("キャンセル") } },
    )
}

@Composable
private fun KeywordFilterModePicker(
    modes: List<KeywordFilterMode?>,
    selected: KeywordFilterMode?,
    enabled: Boolean,
    onSelect: (KeywordFilterMode?) -> Unit,
) {
    if (LocalDensity.current.fontScale >= 1.5f) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            modes.forEach { mode ->
                val checked = mode == selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(checked, enabled = enabled, role = Role.RadioButton) { onSelect(mode) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(checked, onClick = null, enabled = enabled)
                    Text(mode?.label ?: "なし")
                }
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == selected,
                    enabled = enabled,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    label = { Text(mode?.label ?: "なし") },
                )
            }
        }
    }
}
