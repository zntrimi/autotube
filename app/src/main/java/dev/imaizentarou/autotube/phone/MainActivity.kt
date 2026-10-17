package dev.imaizentarou.autotube.phone

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.annotation.OptIn
import dev.imaizentarou.autotube.AppLog
import dev.imaizentarou.autotube.phone.ui.AutoTubeTheme
import dev.imaizentarou.autotube.phone.ui.ClipboardCandidate
import dev.imaizentarou.autotube.phone.ui.QueueScreen
import dev.imaizentarou.autotube.phone.ui.SearchScreen
import dev.imaizentarou.autotube.phone.ui.SettingsScreen
import dev.imaizentarou.autotube.phone.ui.clipboardCandidateOf
import dev.imaizentarou.autotube.player.PlayerEngine
import dev.imaizentarou.autotube.queue.QueueItem
import dev.imaizentarou.autotube.queue.QueueStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/** 画面下のスナックバーに出す一言。action があれば右にボタン（取り消しなど）。 */
private data class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/**
 * スマホ側。キュー（メイン）・検索・設定の 3 画面を Compose で切り替える。
 * YouTube アプリの「共有」からの追加と、クリップボードの URL の拾い上げもここ。
 * PlayerView は車に繋がっていないときの確認用で、出力先の付け外しは PlayerEngine に任せる。
 */
class MainActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    private var clipboard by mutableStateOf<ClipboardCandidate?>(null)
    /** 閉じた（×）クリップボードの URL。同じものはもう出さない。 */
    private var dismissedClip: String? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }

        setContent {
            AutoTubeTheme {
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                var screen by rememberSaveable { mutableStateOf(Screen.Queue) }
                val show: (String, String?, (() -> Unit)?) -> Unit = { text, label, action ->
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        // 取り消しボタン付きは押す余裕があるよう長め（約 10 秒）。
                        val duration = if (label != null) SnackbarDuration.Long else SnackbarDuration.Short
                        val result = snackbar.showSnackbar(text, label, duration = duration)
                        if (result == SnackbarResult.ActionPerformed) action?.invoke()
                    }
                }
                LaunchedEffect(Unit) {
                    for (message in messages) show(message.text, message.actionLabel, message.action)
                }
                BackHandler(enabled = screen != Screen.Queue) { screen = Screen.Queue }

                when (screen) {
                    Screen.Queue -> QueueScreen(
                        playerView = playerView,
                        snackbar = snackbar,
                        clipboard = clipboard,
                        onAddClipboard = { candidate, playNext -> addClipboard(candidate, playNext) },
                        onDismissClipboard = {
                            dismissedClip = clipboard?.url
                            clipboard = null
                        },
                        onOpenSearch = { screen = Screen.Search },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenInYouTube = ::openInYouTube,
                        onMessage = show,
                    )
                    Screen.Search -> SearchScreen(
                        snackbar = snackbar,
                        onBack = { screen = Screen.Queue },
                        onAddUrl = { url ->
                            screen = Screen.Queue
                            addToQueue(url)
                        },
                        onMessage = show,
                    )
                    Screen.Settings -> SettingsScreen(
                        snackbar = snackbar,
                        onBack = { screen = Screen.Queue },
                        onRestart = ::restartProcess,
                        onMessage = show,
                    )
                }
            }
        }

        requestNotificationPermission()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        AppLog.d("App", "activity start")
        PlayerEngine.attachPhoneView(playerView)
    }

    override fun onStop() {
        AppLog.d("App", "activity stop")
        PlayerEngine.detachPhoneView(playerView)
        super.onStop()
    }

    /** クリップボードに YouTube の URL が入っていたら追加ボタンを出す（クリップボードは前面でしか読めない）。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val manager = getSystemService(ClipboardManager::class.java)
        val text = manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val candidate = clipboardCandidateOf(text) ?: run {
            clipboard = null
            return
        }
        if (candidate.url != dismissedClip) clipboard = candidate
    }

    /** 通知に再生コントロールを出すため。media の通知は許可が無くても出るが、念のため頼む。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    /** YouTube アプリの「共有」は text/plain でタイトルと URL が来る。URL だけ拾えば足りる。 */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        intent.action = null
        addToQueue(text)
    }

    private fun addClipboard(candidate: ClipboardCandidate, playNext: Boolean) {
        dismissedClip = candidate.url
        clipboard = null
        addToQueue(candidate.url, playNext)
    }

    private fun addToQueue(text: String, playNext: Boolean = false) {
        messages.trySend(UiMessage("追加中…"))
        lifecycleScope.launch {
            QueueStore.add(text)
                .onSuccess { result ->
                    val single = result.added.singleOrNull()
                    if (single != null && playNext) QueueStore.moveAfter(single.id, PlayerEngine.state.value.current?.id)
                    val message = when {
                        single != null && result.skipped == 0 -> UiMessage(
                            if (playNext) "次に再生: ${single.title}" else "追加: ${single.title}",
                            "取り消す",
                        ) { QueueStore.remove(single.id) }
                        result.added.isEmpty() -> UiMessage("すでにキューにあります")
                        else -> UiMessage("${result.added.size} 本追加（${result.skipped} 本は重複）")
                    }
                    messages.trySend(message)
                }
                .onFailure { messages.trySend(UiMessage("追加できません: ${it.message}")) }
        }
    }

    private fun openInYouTube(item: QueueItem) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
    }

    /** プロセスごと落として起動し直す。ExoPlayer やホストとの接続が変な状態で固まったとき用。 */
    private fun restartProcess() {
        AppLog.w("App", "restart requested by user")
        val relaunch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 500, relaunch)
        exitProcess(0)
    }

    private enum class Screen { Queue, Search, Settings }
}
