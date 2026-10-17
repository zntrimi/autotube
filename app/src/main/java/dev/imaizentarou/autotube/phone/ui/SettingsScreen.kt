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
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "戻る") } },
                title = { Text("設定") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("再生")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("画質の上限", style = MaterialTheme.typography.bodyLarge)
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
                        ) { Text("${height}p") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "次に再生する動画から効きます。回線が細いときは自動で下げます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SwitchRow("車に繋いだら続きから自動再生", null, autoPlay) {
                autoPlay = it
                Settings.autoPlayOnConnect = it
            }
            SwitchRow("スポンサー枠・宣伝を飛ばす", "SponsorBlock の公開データを使います（動画 ID が送られます）", skipSponsors) {
                skipSponsors = it
                Settings.skipSponsors = it
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("安定して動かすために")
            ActionRow(
                title = "バッテリー最適化から外す",
                detail = if (batteryExempt) "外してあります" else "画面を消したときに止められにくくなります",
                enabled = !batteryExempt,
            ) {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("困ったとき")
            Text(
                "止まっても、ふだんは自動で立て直します。手で直すときは、車でもスマホでも ▶ を押せば続きから読み込み直します。" +
                    "それでも直らなければ、上から順に試してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ActionRow("1. 再生を立て直す", "動画を取り直して、同じ位置から再生します") {
                PlayerEngine.restartCurrent()
                onMessage("立て直しています…", null, null)
            }
            ActionRow("2. 再生をすべてリセット", "再生を止めて、取得済みのデータを全部捨てます（キューと再生位置は残ります）") {
                PlayerEngine.reset()
                onMessage("リセットしました。▶ で続きから再生します", null, null)
            }
            ActionRow("3. アプリを再起動", "キューと再生位置は残ります") { confirm = Confirm.Restart }
            ActionRow("ログを見る・共有する", "直らなかったときは、ここから共有してもらえれば原因を追えます") {
                context.startActivity(Intent(context, LogActivity::class.java))
            }
            ActionRow("キューを空にする", null, destructive = true) { confirm = Confirm.ClearQueue }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Android Auto 側: 開発者設定で「提供元不明のアプリ」をオンにすると、ランチャーに AutoTube が出ます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    when (confirm) {
        Confirm.Restart -> ConfirmDialog("アプリを再起動しますか？", "キューと再生位置は残ります。", "再起動", onDismiss = { confirm = null }) {
            onRestart()
        }
        Confirm.ClearQueue -> ConfirmDialog("キューを空にしますか？", "すべての動画が消えます。", "空にする", onDismiss = { confirm = null }) {
            val snapshot = QueueStore.items.value
            PlayerEngine.stop()
            QueueStore.clear()
            onMessage("キューを空にしました", "元に戻す") { QueueStore.replaceAll(snapshot) }
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
