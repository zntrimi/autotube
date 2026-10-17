package dev.imaizentarou.autotube.car

import android.graphics.Rect
import android.os.SystemClock
import dev.imaizentarou.autotube.AppLog
import androidx.annotation.DrawableRes
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.imaizentarou.autotube.R
import dev.imaizentarou.autotube.player.PlayerEngine
import dev.imaizentarou.autotube.player.PlayerState
import dev.imaizentarou.autotube.queue.QueueItem
import dev.imaizentarou.autotube.queue.QueueStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * NavigationTemplate 1 枚。地図用 Surface はまるごと ExoPlayer に渡す（Canvas 描画と MediaCodec は
 * 同じ Surface に同居できないので、こちらでは何も描かない）。タイトルや状態は NavigationInfo のカードで出す。
 */
class PlayerScreen(carContext: CarContext) : Screen(carContext) {
    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val surface = surfaceContainer.surface ?: return
            AppLog.d(TAG, "surface available ${surfaceContainer.width}x${surfaceContainer.height}")
            PlayerEngine.attachCarSurface(surface, surfaceContainer.width, surfaceContainer.height)
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            AppLog.d(TAG, "visible area $visibleArea (card=$cardVisible)")
            // 自分のカードも可視領域から引かれてしまう。カードは一時的なので、出ている間の
            // 更新は無視し、消えたあとにホストが送ってくる領域でレイアウトを決める。
            if (!cardVisible) PlayerEngine.setCarVisibleArea(visibleArea)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            AppLog.d(TAG, "surface destroyed")
            surfaceContainer.surface?.let { PlayerEngine.detachCarSurface(it) }
        }
    }

    init {
        // AutoLyrics で踏んだ落とし穴: Screen 生成の瞬間はホスト未接続のことがあるので、表示のたびに登録し直す。
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = attachSurfaceCallback()
            override fun onResume(owner: LifecycleOwner) = attachSurfaceCallback()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(PlayerEngine.state, QueueStore.items) { state, items -> templateKey(state, items) }
                        .distinctUntilChanged()
                        .collect { invalidate() }
                }
                // 動画が替わったら数秒だけタイトルを見せて、あとは映像の邪魔にならないよう消す。
                launch {
                    PlayerEngine.state.map { it.current?.id }.distinctUntilChanged().collectLatest { id ->
                        if (id == null) return@collectLatest
                        titleVisibleUntil = SystemClock.elapsedRealtime() + TITLE_VISIBLE_MILLIS
                        invalidate()
                        delay(TITLE_VISIBLE_MILLIS)
                        invalidate()
                    }
                }
            }
        }
    }

    private var titleVisibleUntil = 0L

    private fun templateKey(state: PlayerState, items: List<QueueItem>) =
        listOf(state.current?.id, state.playWhenReady, state.statusText, items.map { it.id to it.watched })

    override fun onGetTemplate(): Template {
        val state = PlayerEngine.state.value
        val playback = ActionStrip.Builder()
            .addAction(action(R.drawable.ic_skip_previous) { PlayerEngine.playPrevious() })
            .addAction(action(if (state.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play) { PlayerEngine.togglePlayPause() })
            .addAction(action(R.drawable.ic_skip_next) { PlayerEngine.playNext() })
            .build()
        val tools = ActionStrip.Builder()
            .addAction(action(R.drawable.ic_fast_rewind) { PlayerEngine.seekBy(-10_000) })
            .addAction(action(R.drawable.ic_fast_forward) { PlayerEngine.seekBy(30_000) })
            .addAction(action(R.drawable.ic_stop) { PlayerEngine.stop() })
            .build()
        val builder = NavigationTemplate.Builder()
            .setActionStrip(playback)
            .setMapActionStrip(tools)
        val info = messageInfo(state)
        cardVisible = info != null
        info?.let { builder.setNavigationInfo(it) }
        return builder.build()
    }

    /** 今のテンプレートに NavigationInfo のカードを付けているか。可視領域の扱いに使う。 */
    private var cardVisible = false

    /** 見せるべき状態のときだけカードを返す。順調に再生中で、切り替え直後でもなければ null（カード無し）。 */
    private fun messageInfo(state: PlayerState): MessageInfo? {
        val current = state.current
        val items = QueueStore.items.value
        val unwatched = items.count { !it.watched }
        if (current == null) {
            return MessageInfo.Builder(if (unwatched == 0) "キューが空です" else "▶ で再生")
                .setText(if (unwatched == 0) "スマホの AutoTube から動画を追加" else "$unwatched 本待機中")
                .build()
        }
        val status = state.statusText
        if (status == null && SystemClock.elapsedRealtime() >= titleVisibleUntil) return null

        val position = items.indexOfFirst { it.id == current.id }
        val place = if (position >= 0) "${position + 1}/${items.size}" else null
        val text = listOfNotNull(current.uploader.takeIf { it.isNotBlank() }, place, status).joinToString(" · ")
        return MessageInfo.Builder(current.title)
            .setText(text.ifEmpty { current.durationLabel })
            .build()
    }

    private fun attachSurfaceCallback() {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        } catch (e: Exception) {
            AppLog.w(TAG, "setSurfaceCallback failed; will retry on next lifecycle event", e)
        }
    }

    private fun action(@DrawableRes icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()

    private companion object {
        const val TAG = "AutoTube.Screen"
        const val TITLE_VISIBLE_MILLIS = 5_000L
    }
}
