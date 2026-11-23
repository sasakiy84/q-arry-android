package io.github.sasakiy84.qarry

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

internal data class NotificationSettings(
    val defaultEnabled: Boolean,
    val canPost: Boolean,
    val onDefaultChange: (Boolean) -> Unit,
    val requestPermission: () -> Unit,
    val openSystemSettings: () -> Unit,
)

@Composable
internal fun rememberNotificationSettings(): NotificationSettings {
    val context = LocalContext.current
    val notifications = remember(context) { FeedNotifications(context.applicationContext) }
    var defaultEnabled by remember(notifications) { mutableStateOf(notifications.defaultEnabled) }
    var canPost by remember(notifications) { mutableStateOf(notifications.canPost()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canPost = notifications.canPost()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canPost = notifications.canPost()
    }
    val requestPermission = {
        if (!notifications.hasPermission()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    return NotificationSettings(
        defaultEnabled, canPost,
        onDefaultChange = { enabled ->
            notifications.defaultEnabled = enabled
            defaultEnabled = enabled
            if (enabled) requestPermission()
        },
        requestPermission = requestPermission,
        openSystemSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
    )
}

@Composable
internal fun DefaultNotificationSettings(settings: NotificationSettings) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(settings.defaultEnabled, role = Role.Switch, onValueChange = settings.onDefaultChange)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("新着通知の既定値", style = MaterialTheme.typography.titleMedium)
                Text("個別に設定していないフィードに適用", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.defaultEnabled, onCheckedChange = null)
        }
        if (settings.defaultEnabled && !settings.canPost) {
            NotificationAccessMessage(settings.openSystemSettings, Modifier.padding(horizontal = 20.dp))
        }
    }
}

@Composable
internal fun FeedNotificationDialog(
    current: FeedNotificationMode,
    settings: NotificationSettings,
    onClose: () -> Unit,
    onChange: (FeedNotificationMode) -> Unit,
) {
    var selected by rememberSaveable(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("新着通知") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(Modifier.selectableGroup()) {
                    for (choice in FeedNotificationMode.entries) {
                        val label = if (choice == FeedNotificationMode.Default) {
                            val inherited = if (settings.defaultEnabled) "オン" else "オフ"
                            "${choice.label}（$inherited）"
                        } else choice.label
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected == choice, role = Role.RadioButton) { selected = choice }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == choice, onClick = null)
                            Text(label)
                        }
                    }
                }
                if (current.enabled(settings.defaultEnabled) && !settings.canPost) {
                    NotificationAccessMessage(settings.openSystemSettings)
                }
            }
        },
        dismissButton = { TextButton(onClose) { Text("キャンセル") } },
        confirmButton = {
            TextButton({
                if (selected != current) onChange(selected)
                onClose()
            }) { Text("保存") }
        },
    )
}

@Composable
private fun NotificationAccessMessage(onSystemSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("端末の設定で通知を許可してください", style = MaterialTheme.typography.bodySmall)
        TextButton(onSystemSettings) { Text("端末の通知設定") }
    }
}
