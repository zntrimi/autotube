package dev.imaizentarou.autotube.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import dev.imaizentarou.autotube.youtube.YouTubeExtractor
import java.io.IOException

/**
 * googlevideo への要求を 4MB ずつの範囲指定に分けて投げる DataSource。
 *
 * ExoPlayer は普通 `Range: bytes=N-`（末尾なし）で 1 本の接続を読み続けるが、YouTube は
 * 10MB を超える範囲の要求を最初の 8〜10MB だけ速く送り、あとは約 3.3Mbps に絞る（2026-10 に curl で確認）。
 * 720p60 の映像だけで 4.2Mbps あるので、回線が太くても 20 秒ほどで追いつかなくなっていた。
 * 10MB 以下の範囲なら毎回全速で返ってくるので、細かく区切って繋ぎ直す。
 *
 * ついでに URL の失効（403）もここで吸収する。区切るので数秒ごとに新しい要求を出すことになり、
 * 回線が替わった直後の要求は必ず 403 になるため。
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(private val upstream: DataSource) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    /** 次に読むバイトの絶対位置。 */
    private var position = 0L
    /** 読み終わりの絶対位置（含まない）。分からなければ C.LENGTH_UNSET。 */
    private var end = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        // 前に失効して差し替えた URL があれば最初からそちらを使う（シークで開き直したとき）。
        val latest = YouTubeExtractor.latestStreamUrl(dataSpec.uri.toString())
        spec = if (latest == dataSpec.uri.toString()) dataSpec else dataSpec.withUri(Uri.parse(latest))
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else C.LENGTH_UNSET.toLong()
        openChunk()
        if (end == C.LENGTH_UNSET.toLong()) totalLength()?.let { end = it }
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - dataSpec.position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
            val read = upstream.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                chunkRemaining -= read
                return read
            }
            // 頼んだ分より手前で切れたなら、そこがファイルの終わり。
            if (chunkRemaining > 0) return C.RESULT_END_OF_INPUT
            if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
            upstream.close()
            openChunk()
            if (chunkRemaining == 0L) return C.RESULT_END_OF_INPUT
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        upstream.close()
    }

    private fun openChunk() {
        val spec = spec ?: throw IllegalStateException("not opened")
        val wanted = if (end == C.LENGTH_UNSET.toLong()) CHUNK_BYTES else minOf(CHUNK_BYTES, end - position)
        val opened = try {
            upstream.open(spec.buildUpon().setPosition(position).setLength(wanted).build())
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 403 && e.responseCode != 410) throw e
            // URL の失効（回線が替わって IP が変わった等）。止めずに新しい URL で同じ位置から続ける。
            upstream.close()
            val fresh = YouTubeExtractor.refreshStreamUrl(spec.uri.toString())
                ?: throw StreamRefreshException("stream url expired (${e.responseCode}) and could not be refreshed", e)
            this.spec = spec.withUri(Uri.parse(fresh))
            upstream.open(this.spec!!.buildUpon().setPosition(position).setLength(wanted).build())
        }
        chunkRemaining = if (opened == C.LENGTH_UNSET.toLong()) wanted else opened
        // 終わりが分からないまま短く返ってきたら、それが最後の塊。
        if (end == C.LENGTH_UNSET.toLong() && chunkRemaining < wanted) end = position + chunkRemaining
    }

    /** `Content-Range: bytes 0-4194303/150524867` の全体長。 */
    private fun totalLength(): Long? = upstream.responseHeaders.entries
        .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
        ?.value?.firstOrNull()
        ?.substringAfterLast('/')?.trim()?.toLongOrNull()

    /** 失効した URL を取り直せなかった（たいてい回線が無い）。待てば直るので、読み込み側は回線エラーと同じに扱う。 */
    class StreamRefreshException(message: String, cause: Throwable) : IOException(message, cause)

    private companion object {
        const val CHUNK_BYTES = 4L * 1024 * 1024
    }
}
