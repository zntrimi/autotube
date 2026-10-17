package dev.imaizentarou.autotube.youtube

import android.net.Uri
import android.os.SystemClock
import dev.imaizentarou.autotube.AppLog
import dev.imaizentarou.autotube.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.ConcurrentHashMap

data class VideoMeta(
    val videoId: String,
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
)

/**
 * ExoPlayer に渡す URL の組。audioUrl が null なら videoUrl だけで音声込み（プログレッシブ）。
 * null でなければ映像のみ＋音声のみを MergingMediaSource で合成する。
 */
data class StreamSelection(
    val videoUrl: String,
    val audioUrl: String?,
    val label: String,
)

object YouTubeExtractor {
    private const val TAG = "AutoTube.Extractor"
    private const val PLAYLIST_MAX_ITEMS = 200

    // ストリーム URL は 6 時間で失効する。余裕を見て 45 分だけ使い回す。
    private const val STREAM_CACHE_MILLIS = 45 * 60 * 1000L
    private const val EXTRACT_TIMEOUT_MILLIS = 60_000L
    private const val REFRESH_REUSE_MILLIS = 20_000L

    private val videoIdPattern = Regex("""(?:v=|youtu\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})""")
    private val playlistIdPattern = Regex("""[?&]list=([A-Za-z0-9_-]+)""")

    private class CachedInfo(val info: StreamInfo, val fetchedAt: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheMutex = Mutex()
    private val cache = HashMap<String, CachedInfo>()
    private val inFlight = HashMap<String, Deferred<StreamInfo>>()

    /** 再生に渡したストリーム URL → 元の watch URL。失効したときに取り直す先。 */
    private val streamOrigins = ConcurrentHashMap<String, String>()
    /** 失効したストリーム URL → 取り直した新しい URL。シークで開き直すときもこちらを使う。 */
    private val replacedUrls = ConcurrentHashMap<String, String>()

    /** 貼り付けや共有で来た文字列から動画 ID を拾い、watch URL に正規化する。 */
    fun canonicalUrl(input: String): String? {
        val id = videoIdPattern.find(input)?.groupValues?.get(1) ?: return null
        return "https://www.youtube.com/watch?v=$id"
    }

    /** 動画 ID を含まず `list=` だけある URL（再生リストのページ）なら、その URL を返す。 */
    fun playlistUrl(input: String): String? {
        if (videoIdPattern.containsMatchIn(input)) return null
        val id = playlistIdPattern.find(input)?.groupValues?.get(1) ?: return null
        return "https://www.youtube.com/playlist?list=$id"
    }

    fun videoId(url: String): String = videoIdPattern.find(url)?.groupValues?.get(1) ?: url

    suspend fun meta(url: String): VideoMeta {
        val info = streamInfo(url)
        return VideoMeta(
            videoId = info.id,
            url = info.url,
            title = info.name,
            uploader = info.uploaderName.orEmpty(),
            durationSec = info.duration,
            thumbnailUrl = info.thumbnails.maxByOrNull { it.width }?.url,
        )
    }

    /** 再生リストの動画一覧。動画ごとの情報取得はしないので速い。 */
    suspend fun playlist(url: String): List<VideoMeta> = withContext(Dispatchers.IO) {
        val service = NewPipe.getServiceByUrl(url)
        val first = PlaylistInfo.getInfo(service, url)
        val items = ArrayList<StreamInfoItem>(first.relatedItems)
        var next: Page? = first.nextPage
        while (next != null && items.size < PLAYLIST_MAX_ITEMS) {
            val page = PlaylistInfo.getMoreItems(service, url, next)
            items += page.items
            next = page.nextPage
        }
        items.take(PLAYLIST_MAX_ITEMS).mapNotNull(::metaOf)
    }

    /** 一覧（再生リスト・検索結果）の 1 件から。動画ごとの情報取得はしない。 */
    fun metaOf(item: StreamInfoItem): VideoMeta? {
        val id = videoIdPattern.find(item.url)?.groupValues?.get(1) ?: return null
        return VideoMeta(
            videoId = id,
            url = "https://www.youtube.com/watch?v=$id",
            title = item.name,
            uploader = item.uploaderName.orEmpty(),
            durationSec = item.duration,
            thumbnailUrl = item.thumbnails.maxByOrNull { it.width }?.url,
        )
    }

    /** 再生中に次の動画の分を取っておく。失敗しても何もしない。 */
    fun prefetch(url: String) {
        scope.async {
            runCatching { streamInfo(url) }
                .onFailure { AppLog.w(TAG, "prefetch failed for $url: ${it.message}") }
        }
    }

    /**
     * 再生候補を良い順に返す。
     * 1. MP4 の映像のみ（設定の上限まで）＋ M4A 音声の合成
     * 2. 形式を問わない映像のみ＋音声の合成
     * 3. 音声込みのプログレッシブ … 360p 止まりだが軽い。回線が細いときの最後の手
     */
    suspend fun streams(url: String): List<StreamSelection> {
        val info = streamInfo(url)
        val maxHeight = Settings.maxHeight
        // AV1 はハードウェアで復号できない端末が多く、ソフト復号だとコマ落ち・詰まりになるので使わない。
        val videoOnly = info.videoOnlyStreams.filter { it.isPlayableUrl() && it.heightOrZero() in 1..maxHeight && !it.isAv1() }
        val audio = info.audioStreams.filter { it.isPlayableUrl() && it.isOriginalTrack() }
        val progressive = info.videoStreams.filter { it.isPlayableUrl() }

        val bestMp4Video = videoOnly.filter { it.format == MediaFormat.MPEG_4 }.maxWithOrNull(preferredVideo)
        val bestAnyVideo = videoOnly.maxWithOrNull(preferredVideo)
        val bestM4a = audio.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
        val bestAudio = audio.maxByOrNull { it.averageBitrate }
        val bestProgressive = progressive.maxByOrNull { it.heightOrZero() }

        return buildList {
            if (bestMp4Video != null && bestM4a != null) {
                add(StreamSelection(bestMp4Video.content, bestM4a.content, "${bestMp4Video.getResolution()} mp4+m4a"))
            }
            if (bestAnyVideo != null && bestAudio != null && (bestAnyVideo !== bestMp4Video || bestAudio !== bestM4a)) {
                add(StreamSelection(bestAnyVideo.content, bestAudio.content, "${bestAnyVideo.getResolution()} ${bestAnyVideo.format?.name}+${bestAudio.format?.name}"))
            }
            if (bestProgressive != null) {
                add(StreamSelection(bestProgressive.content, null, "${bestProgressive.getResolution()} progressive"))
            }
        }
    }

    /** ExoPlayer に渡す URL を覚えておく。失効したら [refreshStreamUrl] で同じ itag の新しい URL に替える。 */
    fun registerStreamUrl(streamUrl: String, watchUrl: String) {
        streamOrigins[streamUrl] = watchUrl
    }

    /** 失効して差し替えた URL があればそちら。 */
    fun latestStreamUrl(streamUrl: String): String {
        var url = streamUrl
        repeat(8) { url = replacedUrls[url] ?: return url }
        return url
    }

    /**
     * 失効したストリーム URL の代わりを取ってくる（ExoPlayer の読み込みスレッドから呼ぶのでブロックする）。
     * 回線が替わって IP が変わると URL は 403 になるが、プレイヤーを止めて準備し直すと先読みした分を捨ててしまう。
     * 読み込みの裏で URL だけ差し替えれば、映像は貯めた分で途切れずに続く。
     * 映像と音声がほぼ同時に失効するので、20 秒以内に取り直した結果があればそれを使う。
     */
    fun refreshStreamUrl(staleUrl: String): String? {
        val watchUrl = streamOrigins[staleUrl] ?: return null
        val itag = Uri.parse(staleUrl).getQueryParameter("itag")?.toIntOrNull() ?: return null
        return runBlocking {
            val id = videoId(watchUrl)
            cacheMutex.withLock {
                val cached = cache[id]
                if (cached != null && SystemClock.elapsedRealtime() - cached.fetchedAt > REFRESH_REUSE_MILLIS) cache.remove(id)
            }
            val info = runCatching { streamInfo(watchUrl) }
                .onFailure { AppLog.w(TAG, "refresh failed for $id: ${it.message}") }
                .getOrNull() ?: return@runBlocking null
            val fresh = info.videoOnlyStreams.firstOrNull { it.itag == itag }?.content
                ?: info.audioStreams.firstOrNull { it.itag == itag }?.content
                ?: info.videoStreams.firstOrNull { it.itag == itag }?.content
                ?: return@runBlocking null
            if (fresh == staleUrl) return@runBlocking null
            AppLog.i(TAG, "refreshed stream url for $id itag=$itag")
            streamOrigins[fresh] = watchUrl
            replacedUrls[staleUrl] = fresh
            fresh
        }
    }

    /** キャッシュを捨てて取り直す（失効・無効になったとき用）。 */
    suspend fun invalidate(url: String) {
        cacheMutex.withLock { cache.remove(videoId(url)) }
    }

    /**
     * 回線が替わったとき用。ストリーム URL は取得したときの IP でしか使えない（`ip=` が入っている）ので、
     * Wi-Fi から 5G に替わったら手元の分は全部 403 になる。取得中のものは待っている側がいるので触らない。
     */
    suspend fun invalidateAll() {
        cacheMutex.withLock { cache.clear() }
    }

    /** 「再生をリセット」用。取得中のものも含めて全部捨てる。 */
    fun clearCache() {
        scope.launch {
            cacheMutex.withLock {
                cache.clear()
                inFlight.values.forEach { it.cancel() }
                inFlight.clear()
            }
        }
    }

    /**
     * StreamInfo の取得。同じ動画への同時要求は 1 本にまとめ、結果は 45 分使い回す。
     * 初回はプロセス内で YouTube の player JS を解析するので 10 秒以上かかることがある。
     */
    private suspend fun streamInfo(url: String): StreamInfo {
        val id = videoId(url)
        val now = SystemClock.elapsedRealtime()
        val deferred = cacheMutex.withLock {
            cache[id]?.takeIf { now - it.fetchedAt < STREAM_CACHE_MILLIS }?.let { return it.info }
            inFlight[id] ?: scope.async {
                try {
                    val started = SystemClock.elapsedRealtime()
                    val info = StreamInfo.getInfo(url)
                    AppLog.i(TAG, "extracted $id in ${SystemClock.elapsedRealtime() - started}ms: video-only=${info.videoOnlyStreams.size} audio=${info.audioStreams.size} progressive=${info.videoStreams.size}")
                    cacheMutex.withLock { cache[id] = CachedInfo(info, SystemClock.elapsedRealtime()) }
                    info
                } catch (e: Exception) {
                    AppLog.w(TAG, "extract failed for $id", e)
                    throw e
                } finally {
                    cacheMutex.withLock { inFlight.remove(id) }
                }
            }.also { inFlight[id] = it }
        }
        // 抽出が固まると「取得中…」のまま何も起きないので、待つのは 60 秒まで。
        return try {
            withTimeout(EXTRACT_TIMEOUT_MILLIS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            AppLog.w(TAG, "extract timed out for $id")
            throw IllegalStateException("ストリームの取得がタイムアウトしました（${EXTRACT_TIMEOUT_MILLIS / 1000}秒）")
        }
    }

    /**
     * 高い方を選ぶ。同じ高さなら 30fps 以下を選ぶ（720p60 は 720p30 の倍近いビットレートで、車の画面では差が分からない）。
     */
    private val preferredVideo = compareBy<VideoStream>({ it.heightOrZero() }, { if (it.fps in 1..30) 1 else 0 })

    private fun VideoStream.isAv1() = codec?.startsWith("av01") == true

    private fun VideoStream.isPlayableUrl() = isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
    private fun AudioStream.isPlayableUrl() = isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
    private fun AudioStream.isOriginalTrack() = audioTrackType == null || audioTrackType == AudioTrackType.ORIGINAL

    /** height が入っていないことがあるので "1080p60" のような文字列からも読む。 */
    private fun VideoStream.heightOrZero(): Int {
        if (height > 0) return height
        return Regex("""(\d+)p""").find(getResolution().orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
