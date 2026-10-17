package dev.imaizentarou.autotube.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * MediaSession に見せる Player。ExoPlayer には 1 本ずつしか積まないので、
 * 「次へ」「前へ」を ExoPlayer のプレイリストではなくこちらのキューに向ける。
 * 通知やステアリングキーの次/前ボタンはこれで効くようになる。
 */
@OptIn(UnstableApi::class)
class QueuePlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                PlayerEngine.playNext()
                return Futures.immediateVoidFuture()
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                PlayerEngine.playPrevious()
                return Futures.immediateVoidFuture()
            }
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
}
