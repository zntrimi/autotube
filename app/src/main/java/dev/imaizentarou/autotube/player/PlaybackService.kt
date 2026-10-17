package dev.imaizentarou.autotube.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.imaizentarou.autotube.phone.MainActivity

/**
 * MediaSession を持つだけのサービス。media3 が通知（再生/一時停止/次/前）と
 * フォアグラウンド化を面倒見てくれる。プレイヤー本体は PlayerEngine が持つ。
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, QueuePlayer(PlayerEngine.player))
            .setSessionActivity(openApp)
            .build()
            // onGetSession() で返すだけだとコントローラが繋ぐまでサービスが知らないままで、
            // 通知もフォアグラウンド化もされない。最初から登録しておく。
            .also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // スマホ側のアプリを閉じても、車で再生中なら止めない。
        if (PlayerEngine.player.playWhenReady) return
        stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            // プレイヤーは PlayerEngine が使い続けるので release しない。
            release()
        }
        session = null
        super.onDestroy()
    }
}
