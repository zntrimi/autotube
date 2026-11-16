package dev.imaizentarou.autotube.phone.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.imaizentarou.autotube.R
import dev.imaizentarou.autotube.Settings
import dev.imaizentarou.autotube.phone.LogActivity
import dev.imaizentarou.autotube.player.PlayerEngine
import dev.imaizentarou.autotube.queue.QueueStore

/** 設定。変えた瞬間に保存する（OK ボタンは無い）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onMessage: (text: String, actionLabel: String?, action: (() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    var maxHeight by remember { mutableStateOf(Settings.maxHeight) }
    var autoPlay by remember { mutableStateOf(Settings.autoPlayOnConnect) }
    var skipSponsors by remember { mutableStateOf(Settings.skipSponsors) }
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var batteryExempt by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    // 設定アプリから戻ってきたら反映する。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.settings_back)) } },
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.settings_section_playback))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_max_quality), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Settings.maxHeightChoices.forEachIndexed { index, height ->
                        SegmentedButton(
                            selected = maxHeight == height,
                            onClick = {
                                maxHeight = height
                                Settings.maxHeight = height
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, Settings.maxHeightChoices.size),
                        ) { Text(stringResource(R.string.settings_quality_label, height)) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_quality_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SwitchRow(stringResource(R.string.settings_autoplay), null, autoPlay) {
                autoPlay = it
                Settings.autoPlayOnConnect = it
            }
            SwitchRow(stringResource(R.string.settings_sponsorblock_title), stringResource(R.string.settings_sponsorblock_detail), skipSponsors) {
                skipSponsors = it
                Settings.skipSponsors = it
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.settings_section_stability))
            ActionRow(
                title = stringResource(R.string.settings_battery_title),
                detail = if (batteryExempt) stringResource(R.string.settings_battery_done) else stringResource(R.string.settings_battery_hint),
                enabled = !batteryExempt,
            ) {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.settings_section_trouble))
            Text(
                stringResource(R.string.settings_trouble_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ActionRow(stringResource(R.string.settings_rebuild_title), stringResource(R.string.settings_rebuild_detail)) {
                PlayerEngine.restartCurrent()
                onMessage(context.getString(R.string.settings_rebuilding), null, null)
            }
            ActionRow(stringResource(R.string.settings_reset_title), stringResource(R.string.settings_reset_detail)) {
                PlayerEngine.reset()
                onMessage(context.getString(R.string.settings_reset_done), null, null)
            }
            ActionRow(stringResource(R.string.settings_restart_title), stringResource(R.string.settings_restart_detail)) { confirm = Confirm.Restart }
            ActionRow(stringResource(R.string.settings_logs_title), stringResource(R.string.settings_logs_detail)) {
                context.startActivity(Intent(context, LogActivity::class.java))
            }
            ActionRow(stringResource(R.string.settings_clear_queue_title), null, destructive = true) { confirm = Confirm.ClearQueue }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.settings_aa_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    when (confirm) {
        Confirm.Restart -> ConfirmDialog(
            stringResource(R.string.settings_restart_confirm_title),
            stringResource(R.string.settings_restart_confirm_text),
            stringResource(R.string.settings_restart_confirm_ok),
            onDismiss = { confirm = null },
        ) {
            onRestart()
        }
        Confirm.ClearQueue -> ConfirmDialog(
            stringResource(R.string.settings_clear_confirm_title),
            stringResource(R.string.settings_clear_confirm_text),
            stringResource(R.string.settings_clear_confirm_ok),
            onDismiss = { confirm = null },
        ) {
            val snapshot = QueueStore.items.value
            PlayerEngine.stop()
            QueueStore.clear()
            onMessage(context.getString(R.string.settings_queue_cleared), context.getString(R.string.settings_undo)) { QueueStore.replaceAll(snapshot) }
        }
        null -> Unit
    }
}

private enum class Confirm { Restart, ClearQueue }

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(title: String, detail: String?, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                destructive -> MaterialTheme.colorScheme.error
                else -> Color.Unspecified
            },
        )
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
