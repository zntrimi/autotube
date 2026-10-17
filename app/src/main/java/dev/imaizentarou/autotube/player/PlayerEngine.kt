package dev.imaizentarou.autotube.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import dev.imaizentarou.autotube.AppLog
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.PlayerView
import dev.imaizentarou.autotube.Settings
import dev.imaizentarou.autotube.queue.QueueItem
import dev.imaizentarou.autotube.queue.QueueStore
import dev.imaizentarou.autotube.youtube.OkHttpDownloader
import dev.imaizentarou.autotube.youtube.SkipSegment
import dev.imaizentarou.autotube.youtube.SponsorBlock
import dev.imaizentarou.autotube.youtube.StreamSelection
import dev.imaizentarou.autotube.youtube.YouTubeExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import java.io.IOException

data class PlayerState(
    val current: QueueItem? = null,
    /** 再生しようとしているか（一時停止でない）。 */
    val playWhenReady: Boolean = false,
    /** 実際に進んでいるか。 */
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** ストリーム URL を取りに行っている間。 */
    val isLoading: Boolean = false,
    val streamLabel: String? = null,
    val error: String? = null,
    val carAttached: Boolean = false,
    /** インターネットに出られない（圏外・トンネル）。戻れば勝手に続きから読み込む。 */
    val offline: Boolean = false,
) {
    /** 車側カードとスマホ側で共通の状態文。順調に再生中なら null。 */
    val statusText: String?
        get() = when {
            // 中身（技術的な理由）はログへ。車でもスマホでも、することは ▶ を押すだけ。
            error != null -> "再生できませんでした · 自動でやり直します（▶ ですぐ）"
            offline && (isLoading || isBuffering) -> "回線待ち…（戻ったら続きから）"
            isLoading -> "ストリームを取得中…"
            !playWhenReady -> "一時停止中"
            isBuffering -> "読み込み中…"
            else -> null
        }
}

/**
 * プロセスに1つの ExoPlayer。出力先は車の Surface が最優先で、無ければスマホの PlayerView。
 * 出力先やエフェクトの切り替えは次の prepare() から効くので、切り替えたら同じ位置から準備し直す。
 *
 * オーディオフォーカスは自前で扱う。再生開始時に要求して Spotify などを止めるが、
 * 奪われても止まらない（YouTube アプリで次の動画を開いて共有するたびに車側が止まると困る）。
 */
@OptIn(UnstableApi::class)
object PlayerEngine {
    private const val TAG = "AutoTube.Player"
    private const val STALL_TIMEOUT_MILLIS = 20_000L
    private const val MAX_RECOVERIES = 3
    private const val POSITION_SAVE_INTERVAL_MILLIS = 10_000L
    /** URL の取り直し・同じ候補での準備し直しの上限（画質は落とさない立て直し）。READY になったら数え直す。 */
    private const val MAX_REFRESHES = 4
    /** 映像の縮小・配置（エフェクト）が何回失敗したら諦めて全面表示にするか。 */
    private const val MAX_EFFECT_FAILURES = 2
    private const val EXTRACT_RETRIES = 3
    private const val MAX_CONSECUTIVE_UNAVAILABLE = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    lateinit var player: ExoPlayer
        private set
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var bandwidthMeter: DefaultBandwidthMeter
    private lateinit var dataSourceFactory: DataSource.Factory

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var carSurface: Surface? = null
    private var carWidth = 0
    private var carHeight = 0
    private val carLayout = CarLayoutTransformation()
    private var phoneView: PlayerView? = null

    private var candidates: List<StreamSelection> = emptyList()
    private var candidateIndex = 0
    private var currentSelection: StreamSelection? = null
    private var loadJob: Job? = null
    /** 同じ動画で何回詰まり・失敗から立て直したか。候補を一段ずつ落としていく。 */
    private var recoveryCount = 0
    private var prefetchedId: String? = null
    /** 同じ動画で、画質を落とさずに立て直した回数（URL の失効・回線断・描画の失敗）。 */
    private var refreshCount = 0
    private var effectFailures = 0
    /** エフェクトが壊れる端末・状況では、以後は縦横比を諦めて上下を切った全面表示にする。 */
    private var effectsDisabled = false
    private var currentNetwork: Network? = null
    /** URL 取得中（プレイヤーはまだ空）に回線が替わったとき、どこから再開するか。 */
    private var consecutiveUnavailable = 0
    private var autoRetryJob: Job? = null
    /** 同じ動画で続けて自動のやり直しをした回数。間隔を 10 → 20 → 40 → 60 秒と延ばす。READY で数え直す。 */
    private var autoRetryCount = 0
    private var loadingStartMs = 0L
    private var loadingPlayWhenReady = false

    private var stallBuffered = -1L
    private var stallSince = 0L
    private var lastPositionSave = 0L

    /** 今の動画で飛ばす区間（SponsorBlock）。取得が終わるまでは空。 */
    private var skipSegments: List<SkipSegment> = emptyList()
    private var skipSegmentsJob: Job? = null
    private var lastSkippedEndSec = -1.0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {}
        override fun onServiceDisconnected(name: ComponentName) {}
    }

    fun initialize(context: Context) {
        if (::player.isInitialized) return
        app = context.applicationContext
        bandwidthMeter = DefaultBandwidthMeter.Builder(app).build()
        dataSourceFactory = ChunkedDataSource.Factory(
            DefaultHttpDataSource.Factory()
                .setUserAgent(OkHttpDownloader.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setTransferListener(bandwidthMeter),
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        audioManager = app.getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes.platformAudioAttributes)
            .setOnAudioFocusChangeListener { change ->
                // 意図的に無視する。止めたいときは車かスマホのボタンで止める。
                AppLog.i(TAG, "audio focus change ignored: $change")
            }
            .build()

        player = ExoPlayer.Builder(app)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false)
            // 出力経路の切り替え（イヤホン抜き等）で止めない。車では経路が変わっても続けてほしい。
            .setHandleAudioBecomingNoisy(false)
            .setBandwidthMeter(bandwidthMeter)
            // 既定（50 秒先まで）よりずっと多く、最大 5 分先まで貯める。トンネルや圏外を貯金でしのぐため。
            // 量の上限は 96MB（720p30 なら 5 分ぶん収まる。1080p では 3 分ほど）。
            // 再生開始・シーク後は 1.5 秒貯まれば動き出す。
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(60_000, 300_000, 1_500, 3_000)
                    .setTargetBufferBytes(96 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
            // 再生中は CPU と Wi-Fi を寝かせない。スマホの画面が消えても車側の再生を続けるため。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AppLog.i(TAG, "isPlaying=$isPlaying suppression=${suppressionName(player.playbackSuppressionReason)} pos=${player.currentPosition}")
                _state.update { it.copy(isPlaying = isPlaying) }
                if (!isPlaying) savePosition(force = true)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                AppLog.i(TAG, "playWhenReady=$playWhenReady reason=${playWhenReadyReasonName(reason)}")
                _state.update { it.copy(playWhenReady = playWhenReady) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                AppLog.i(TAG, "playbackState=${stateName(playbackState)} pos=${player.currentPosition} buffered=${player.bufferedPosition}")
                _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
                when (playbackState) {
                    Player.STATE_READY -> {
                        _state.value.current?.takeIf { it.unavailable }?.let { item ->
                            QueueStore.update(item.id) { it.copy(unavailable = false) }
                        }
                        refreshCount = 0
                        autoRetryCount = 0
                        consecutiveUnavailable = 0
                        prefetchNext()
                    }
                    Player.STATE_ENDED -> onCurrentEnded()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val format = player.videoFormat ?: return
                if (format.width > 0 && format.height > 0) {
                    carLayout.videoAspect = format.width * format.pixelWidthHeightRatio / format.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.w(TAG, "player error: ${error.errorCodeName}", error)
                handleError(error)
            }
        })
        // ロード・フォーマット・レンダラの動きを logcat（タグ EventLogger）に全部出す。個人用なので常時オン。
        player.addAnalyticsListener(EventLogger())
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                AppLog.w(TAG, "load error (canceled=$wasCanceled): ${error.message} ${loadEventInfo.uri.host}")
            }
        })

        // MediaSession を持つサービスをプロセスと同じ寿命で結び付けておく。
        // 再生が始まると media3 が通知を出してフォアグラウンドにする。
        app.bindService(Intent(app, PlaybackService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        scope.launch {
            while (true) {
                delay(5_000)
                checkStall()
                savePosition(force = false)
            }
        }
        scope.launch {
            while (true) {
                delay(500)
                skipSponsorIfNeeded()
            }
        }
        watchNetwork()
        // 車に繋いだ直後の自動再生に備えて、プロセス起動時に続きの動画の URL を取っておく。
        // 初回の抽出は player JS の解析で 5〜15 秒かかるので、ここで済ませておくと乗ってすぐ映る。
        if (Settings.autoPlayOnConnect) autoPlayCandidate()?.let { YouTubeExtractor.prefetch(it.url) }
    }

    /**
     * 既定の回線を見張る。
     * - 回線が替わった（家の Wi-Fi → 5G など）: 手元の URL は前の IP に結び付いていて使えないので捨てる。
     *   再生中の分は ChunkedDataSource が 403 を受けた時点で裏で差し替えるので、止めずに続く。
     * - 圏外になった・戻った: 「回線待ち」を出し、戻ったらエラー中・詰まり中の動画を立て直す。
     */
    private fun watchNetwork() {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        _state.update { it.copy(offline = !hasInternet()) }
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    val previous = currentNetwork
                    currentNetwork = network
                    val switched = previous != null && previous != network
                    AppLog.i(TAG, "default network $network (switched=$switched)")
                    if (switched) YouTubeExtractor.invalidateAll()
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                scope.launch { setOnline(online) }
            }

            // 既定の回線が無くなった。別の回線に替わるときは直後に onAvailable → onCapabilitiesChanged で戻る。
            override fun onLost(network: Network) {
                scope.launch { setOnline(false) }
            }
        })
    }

    private fun setOnline(online: Boolean) {
        val wasOffline = _state.value.offline
        _state.update { it.copy(offline = !online) }
        if (online == !wasOffline) return
        AppLog.i(TAG, if (online) "back online" else "offline")
        if (!online) return
        val current = _state.value.current ?: return
        val stuck = _state.value.error != null ||
            (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) ||
            (player.playbackState == Player.STATE_IDLE && loadingPlayWhenReady)
        if (!stuck) return
        AppLog.i(TAG, "back online; reloading ${current.videoId}")
        recoveryCount = 0
        refreshCount = 0
        val position = if (player.playbackState == Player.STATE_IDLE && _state.value.isLoading) loadingStartMs else player.currentPosition
        scope.launch {
            YouTubeExtractor.invalidate(current.url)
            load(current, playWhenReady = true, startPositionMs = position, startCandidate = candidateIndex)
        }
    }

    private fun hasInternet(): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ---- 操作 ----

    fun play(item: QueueItem) = load(item, playWhenReady = true, startPositionMs = item.resumePositionMs)

    /**
     * 「再生をリセット」。再生・取得中の処理を全部止め、抽出キャッシュも捨てて初期状態に戻す。
     * 何かが詰まって動かなくなったときの逃げ道。キューは消さない。
     */
    fun reset() {
        AppLog.w(TAG, "reset requested")
        autoRetryJob?.cancel()
        loadJob?.cancel()
        loadJob = null
        skipSegmentsJob?.cancel()
        skipSegments = emptyList()
        currentSelection = null
        candidates = emptyList()
        candidateIndex = 0
        recoveryCount = 0
        prefetchedId = null
        stallSince = 0
        stallBuffered = -1
        player.stop()
        player.clearMediaItems()
        audioManager.abandonAudioFocusRequest(focusRequest)
        YouTubeExtractor.clearCache()
        _state.update { PlayerState(carAttached = it.carAttached) }
    }

    fun togglePlayPause() {
        val current = _state.value.current
        if (current == null) {
            autoPlayCandidate()?.let { play(it) }
            return
        }
        when {
            // エラー中・止まっている（取得中でもない）なら、▶ は「立て直して続きから」。
            _state.value.error != null || (player.playbackState == Player.STATE_IDLE && !_state.value.isLoading) -> restartCurrent()
            player.playWhenReady -> player.pause()
            else -> {
                requestAudioFocus()
                player.play()
            }
        }
    }

    /**
     * 今の動画を立て直して続きから再生する。エラーの「やり直す」・車の ▶・設定の「再生を立て直す」の中身。
     * 取得済みの URL を捨て、画質の段階・やり直しの回数も最初に戻してから取り直す。キューは触らない。
     */
    fun restartCurrent() {
        val item = _state.value.current ?: autoPlayCandidate() ?: return
        val position = when {
            _state.value.current?.id != item.id -> item.resumePositionMs
            _state.value.isLoading -> loadingStartMs
            player.currentPosition > 0 -> player.currentPosition
            else -> QueueStore.find(item.id)?.resumePositionMs ?: 0
        }
        AppLog.w(TAG, "restart ${item.videoId} from ${position}ms")
        autoRetryJob?.cancel()
        recoveryCount = 0
        refreshCount = 0
        effectFailures = 0
        candidateIndex = 0
        scope.launch {
            YouTubeExtractor.invalidate(item.url)
            load(item, playWhenReady = true, startPositionMs = position)
        }
    }

    fun hasNext(): Boolean = QueueStore.nextUnwatchedAfter(_state.value.current?.id) != null

    fun playNext() {
        val next = QueueStore.nextUnwatchedAfter(_state.value.current?.id)
        if (next != null) play(next) else stop()
    }

    fun playPrevious() {
        if (player.currentPosition > 5_000) {
            player.seekTo(0)
            return
        }
        val previous = QueueStore.previousOf(_state.value.current?.id)
        if (previous != null) load(previous, playWhenReady = true, startPositionMs = 0) else player.seekTo(0)
    }

    fun seekBy(deltaMs: Long) {
        if (player.playbackState == Player.STATE_IDLE) return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    fun seekTo(positionMs: Long) {
        if (player.playbackState == Player.STATE_IDLE) return
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    fun stop() {
        autoRetryJob?.cancel()
        savePosition(force = true)
        loadJob?.cancel()
        loadJob = null
        currentSelection = null
        player.stop()
        player.clearMediaItems()
        audioManager.abandonAudioFocusRequest(focusRequest)
        _state.update { PlayerState(carAttached = it.carAttached) }
    }

    // ---- 出力先 ----

    fun attachCarSurface(surface: Surface, width: Int, height: Int) {
        AppLog.i(TAG, "car surface attached ${width}x$height (state=${stateName(player.playbackState)})")
        // ホストは同じ Surface を何度も渡してくることがある（接続直後や画面の再表示）。
        // そのたびに準備し直すと再生が途切れるので、変わっていなければ何もしない。
        if (surface == carSurface && width == carWidth && height == carHeight) return
        carSurface = surface
        carWidth = width
        carHeight = height
        carLayout.surfaceWidth = width
        carLayout.surfaceHeight = height
        _state.update { it.copy(carAttached = true) }
        applyOutputAndReload()
        // 乗ってすぐ続きから。何も積んでいなければ何もしない。
        if (Settings.autoPlayOnConnect && player.playbackState == Player.STATE_IDLE && _state.value.current == null) {
            autoPlayCandidate()?.let { play(it) }
        }
    }

    fun detachCarSurface(surface: Surface) {
        if (carSurface != surface) return
        AppLog.i(TAG, "car surface detached (playing=${player.isPlaying})")
        carSurface = null
        _state.update { it.copy(carAttached = false) }
        applyOutputAndReload()
    }

    /** ホストの可視領域。分割表示のときに映像をカードの中へ寄せる。 */
    fun setCarVisibleArea(area: Rect) {
        carLayout.visibleArea = Rect(area)
        // 一時停止中は新しいフレームが来ないので、同じ位置へシークして描き直させる。
        if (carSurface != null && !player.isPlaying && player.playbackState == Player.STATE_READY) {
            player.seekTo(player.currentPosition)
        }
    }

    /**
     * スマホ側の PlayerView。車に繋がっている間は出力先にならないので、付け外しで再生を触らない
     * （画面オフや折りたたみで Activity が作り直されるたびに止まっていた）。
     * 車が無いときも、エフェクト無しの SurfaceView 差し替えだけなので準備し直しは不要。
     */
    fun attachPhoneView(view: PlayerView) {
        AppLog.d(TAG, "phone view attached (car=${carSurface != null})")
        phoneView = view
        if (carSurface != null) {
            view.player = null
            return
        }
        // 車が外れたときに applyOutput() でエフェクトは空にしてあるので、ビューを差すだけでよい。
        view.player = player
    }

    fun detachPhoneView(view: PlayerView) {
        if (phoneView !== view) return
        AppLog.d(TAG, "phone view detached (car=${carSurface != null}, playing=${player.isPlaying})")
        view.player = null
        phoneView = null
    }

    // ---- 内部 ----

    private fun autoPlayCandidate(): QueueItem? = QueueStore.autoPlayCandidate(Settings.lastPlayedId)

    private fun load(item: QueueItem, playWhenReady: Boolean, startPositionMs: Long, startCandidate: Int = 0) {
        savePosition(force = true)
        loadJob?.cancel()
        autoRetryJob?.cancel()
        currentSelection = null
        if (item.id != _state.value.current?.id) {
            autoRetryCount = 0
            recoveryCount = 0
            refreshCount = 0
            candidateIndex = 0
        }
        player.stop()
        Settings.lastPlayedId = item.id
        if (item.watched) QueueStore.update(item.id) { it.copy(watched = false) }
        loadingStartMs = startPositionMs
        loadingPlayWhenReady = playWhenReady
        _state.update { it.copy(current = item, isLoading = true, error = null, streamLabel = null) }
        AppLog.i(TAG, "load ${item.videoId} '${item.title.take(40)}' from ${startPositionMs}ms candidate=$startCandidate")
        loadSkipSegments(item)
        loadJob = scope.launch {
            // 取得の失敗は数回やり直す。圏外の間は諦めずに待ち続ける（戻れば setOnline からも呼び直される）。
            var attempt = 0
            while (true) {
                try {
                    val found = YouTubeExtractor.streams(item.url)
                    if (found.isEmpty()) throw IllegalStateException("再生できるストリームがありません")
                    AppLog.i(TAG, "streams for ${item.videoId}: ${found.map { it.label }}")
                    candidates = found
                    candidateIndex = startCandidate.coerceIn(0, found.size - 1)
                    prepare(item, found[candidateIndex], startPositionMs, playWhenReady)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ContentNotAvailableException) {
                    // 削除・非公開・年齢制限・地域制限など。待っても直らないので飛ばして次へ。
                    // ただし続けて何本も駄目なら動画のせいではない（YouTube 側の一時的な締め出しなど）ので、
                    // キューを空振りで食い潰さないよう飛ばすのをやめ、普通の失敗として自動のやり直しに回す。
                    consecutiveUnavailable += 1
                    if (consecutiveUnavailable > MAX_CONSECUTIVE_UNAVAILABLE) {
                        AppLog.w(TAG, "$consecutiveUnavailable videos unavailable in a row; not skipping", e)
                        fail(e.message ?: e.javaClass.simpleName)
                        return@launch
                    }
                    AppLog.w(TAG, "${item.videoId} is unavailable; skipping", e)
                    QueueStore.update(item.id) { it.copy(unavailable = true) }
                    val next = QueueStore.nextUnwatchedAfter(item.id)
                    if (next != null) play(next) else stop()
                    return@launch
                } catch (e: Exception) {
                    attempt += 1
                    AppLog.w(TAG, "extract failed for ${item.videoId} (attempt $attempt, offline=${_state.value.offline})", e)
                    if (attempt > EXTRACT_RETRIES && !_state.value.offline) {
                        fail(e.message ?: e.javaClass.simpleName)
                        return@launch
                    }
                    delay(minOf(attempt * 3_000L, 15_000L))
                }
            }
        }
    }

    private fun prepare(item: QueueItem, selection: StreamSelection, positionMs: Long, playWhenReady: Boolean) {
        currentSelection = selection
        applyOutput()
        player.setMediaSource(buildSource(item, selection))
        player.prepare()
        if (positionMs > 0) player.seekTo(positionMs)
        if (playWhenReady) requestAudioFocus()
        player.playWhenReady = playWhenReady
        stallSince = 0
        stallBuffered = -1
        _state.update { it.copy(isLoading = false, streamLabel = selection.label, error = null) }
    }

    private fun requestAudioFocus() {
        val result = audioManager.requestAudioFocus(focusRequest)
        AppLog.d(TAG, "requestAudioFocus -> $result")
    }

    private fun onCurrentEnded() {
        val current = _state.value.current ?: return
        QueueStore.update(current.id) { it.copy(watched = true, positionMs = 0) }
        playNext()
    }

    private fun loadSkipSegments(item: QueueItem) {
        skipSegmentsJob?.cancel()
        skipSegments = emptyList()
        lastSkippedEndSec = -1.0
        if (!Settings.skipSponsors) return
        skipSegmentsJob = scope.launch {
            val segments = SponsorBlock.segments(item.videoId)
            if (_state.value.current?.id != item.id) return@launch
            skipSegments = segments
            if (segments.isNotEmpty()) AppLog.i(TAG, "skip segments for ${item.videoId}: ${segments.map { "${it.category} ${it.startSec.toInt()}-${it.endSec.toInt()}" }}")
        }
    }

    /** 0.5 秒ごとに呼ばれる。スポンサー区間に入ったら終端へ飛ぶ。 */
    private fun skipSponsorIfNeeded() {
        if (skipSegments.isEmpty() || !player.isPlaying) return
        val positionSec = player.currentPosition / 1000.0
        val segment = skipSegments.firstOrNull { positionSec >= it.startSec && positionSec < it.endSec - 0.5 } ?: return
        // 手で戻したときに何度も飛ばされないよう、同じ区間は 1 回だけ。
        if (segment.endSec == lastSkippedEndSec) return
        lastSkippedEndSec = segment.endSec
        AppLog.i(TAG, "skipping ${segment.category} ${segment.startSec}-${segment.endSec}")
        player.seekTo((segment.endSec * 1000).toLong())
    }

    /** 再生中に次の動画のストリーム URL を取っておく。切り替えが一瞬になる。 */
    private fun prefetchNext() {
        val next = QueueStore.nextUnwatchedAfter(_state.value.current?.id) ?: return
        if (next.id == prefetchedId) return
        prefetchedId = next.id
        YouTubeExtractor.prefetch(next.url)
    }

    /** 10 秒ごと、および止まったときに再生位置をキューへ書く。 */
    private fun savePosition(force: Boolean) {
        val current = _state.value.current ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPositionSave < POSITION_SAVE_INTERVAL_MILLIS) return
        lastPositionSave = now
        val position = player.currentPosition
        if (position <= 0) return
        QueueStore.update(current.id) { it.copy(positionMs = position) }
    }

    /**
     * 再生エラーの振り分け。画質を落とす（候補を下げる）のは本当にその形式が駄目なときだけにする。
     * - 映像の縮小・配置（エフェクト）の失敗: ストリームは無関係。同じ候補で準備し直し、続くならエフェクトを切る
     * - 403/410 など: URL の失効や回線の切り替わり。取り直して同じ候補で続ける
     * - 回線の切断・タイムアウト: 少し待って同じ候補で続ける（トンネルなど）
     * - それ以外（復号できない等）: 次の候補へ
     */
    private fun handleError(error: PlaybackException) {
        val item = _state.value.current ?: return
        val selection = currentSelection
        val position = player.currentPosition
        when {
            error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED && selection != null -> {
                effectFailures += 1
                if (effectFailures >= MAX_EFFECT_FAILURES && !effectsDisabled) {
                    AppLog.w(TAG, "video effects failed $effectFailures times; disabling them")
                    effectsDisabled = true
                }
                AppLog.w(TAG, "re-preparing after frame processing failure (#$effectFailures)")
                prepare(item, selection, position, playWhenReady = true)
            }
            error.isUrlRejected() && refreshCount < MAX_REFRESHES -> {
                refreshCount += 1
                AppLog.w(TAG, "url rejected (${error.httpStatus()}); re-extracting #$refreshCount")
                scope.launch {
                    YouTubeExtractor.invalidate(item.url)
                    load(item, playWhenReady = true, startPositionMs = position, startCandidate = candidateIndex)
                }
            }
            // 回線のエラーは何回でもやり直す（読み込み側でも粘るので、ここに来るのは稀）。
            error.isNetworkError() && selection != null -> {
                refreshCount += 1
                val waitMs = minOf(refreshCount * 3_000L, 15_000L)
                AppLog.w(TAG, "network error; retrying same stream in ${waitMs}ms (#$refreshCount)")
                _state.update { it.copy(isBuffering = true) }
                scope.launch {
                    delay(waitMs)
                    if (_state.value.current?.id != item.id || currentSelection !== selection) return@launch
                    prepare(item, selection, position, playWhenReady = true)
                }
            }
            else -> tryNextCandidate(error)
        }
    }

    /**
     * 打つ手が尽きた。止めっぱなしにはせず、間隔を空けて自動で立て直す（10 → 20 → 40 → 60 秒、以後 60 秒ごと）。
     * 運転中に手で直さなくていいように。理由はログにだけ残す。
     */
    private fun fail(reason: String) {
        val item = _state.value.current ?: return
        _state.update { it.copy(isLoading = false, error = reason) }
        autoRetryJob?.cancel()
        val waitMs = minOf(10_000L shl autoRetryCount.coerceAtMost(3), 60_000L)
        autoRetryCount += 1
        AppLog.w(TAG, "playback failed for ${item.videoId} ($reason); auto restart #$autoRetryCount in ${waitMs}ms")
        autoRetryJob = scope.launch {
            delay(waitMs)
            if (_state.value.current?.id == item.id && _state.value.error != null) restartCurrent()
        }
    }

    private fun PlaybackException.httpStatus(): Int? =
        (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode

    private fun PlaybackException.isUrlRejected(): Boolean = httpStatus().let { it == 403 || it == 404 || it == 410 }

    private fun PlaybackException.isNetworkError(): Boolean = errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        cause is ChunkedDataSource.StreamRefreshException ||
        httpStatus().let { it != null && it >= 500 }

    private fun tryNextCandidate(error: PlaybackException) {
        val next = candidateIndex + 1
        val item = _state.value.current
        if (next < candidates.size && item != null) {
            AppLog.w(TAG, "falling back to candidate $next: ${candidates[next].label}")
            candidateIndex = next
            prepare(item, candidates[next], player.currentPosition, playWhenReady = true)
        } else if (recoveryCount < MAX_RECOVERIES) {
            // URL が失効・無効になった可能性。取り直す。
            recover("player error ${error.errorCodeName}")
        } else {
            fail("${error.errorCodeName}: ${error.message.orEmpty()}")
        }
    }

    /**
     * バッファが 20 秒進まなければ立て直す。回線が細いか YouTube 側の帯域制限なので、
     * 同じ URL を取り直しても意味がない。取り直しつつ候補を一段落とす（720p → 別形式 → 360p）。
     */
    private fun checkStall() {
        // 圏外のときは画質のせいではないので数えない。戻れば読み込みが勝手に再開する。
        if (currentSelection == null || player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady || _state.value.offline) {
            stallSince = 0
            return
        }
        val buffered = player.bufferedPosition
        val now = SystemClock.elapsedRealtime()
        if (buffered != stallBuffered) {
            stallBuffered = buffered
            stallSince = now
            return
        }
        if (stallSince > 0 && now - stallSince > STALL_TIMEOUT_MILLIS) {
            if (recoveryCount < MAX_RECOVERIES) {
                recover("stalled at ${player.currentPosition}ms, ~${bandwidthMeter.bitrateEstimate / 1000} kbps")
            } else {
                // 一番軽い候補まで落としても進まない。諦めずに、接続を張り直して同じ候補で続ける。
                val item = _state.value.current ?: return
                val selection = currentSelection ?: return
                AppLog.w(TAG, "still stalled at lowest candidate; reconnecting")
                prepare(item, selection, player.currentPosition, playWhenReady = true)
            }
        }
    }

    private fun recover(reason: String) {
        val item = _state.value.current ?: return
        recoveryCount += 1
        val nextCandidate = (candidateIndex + 1).coerceAtMost(candidates.size - 1)
        AppLog.w(TAG, "recovery #$recoveryCount ($reason): re-extracting ${item.videoId}, candidate $nextCandidate")
        val position = player.currentPosition
        scope.launch {
            YouTubeExtractor.invalidate(item.url)
            load(item, playWhenReady = true, startPositionMs = position, startCandidate = nextCandidate)
        }
    }

    /**
     * 読み込みの失敗をどこまで粘るか。再生エラーまで上がるとプレイヤーが止まり、先読みした分も捨てることになるので、
     * 回線の問題（切断・タイムアウト・5xx・URL を取り直せなかった）は読み込みの中で何回でもやり直す。
     * その間も貯めた分で再生は続き、回線が戻れば続きから読み込む。
     * 403/404/410（ChunkedDataSource で差し替えられなかった失効）は待っても直らないので、すぐエラーにして
     * handleError で取り直す。それ以外の失敗は 6 回までやり直す。
     */
    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy(Int.MAX_VALUE) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val exception = loadErrorInfo.exception
            val status = (exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (status == 403 || status == 404 || status == 410) return C.TIME_UNSET
            val network = exception is ChunkedDataSource.StreamRefreshException ||
                (status != null && status >= 500) ||
                (exception is HttpDataSource.HttpDataSourceException && exception !is HttpDataSource.InvalidResponseCodeException)
            if (network) return minOf(loadErrorInfo.errorCount * 1_000L, 5_000L)
            if (loadErrorInfo.errorCount > 6) return C.TIME_UNSET
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private fun buildSource(item: QueueItem, selection: StreamSelection): MediaSource {
        // 読み込みの失敗は既定（3 回）より粘ってから再生エラーにする。電波の弱い区間の一瞬の切断で止めないため。
        val factory = ProgressiveMediaSource.Factory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
        // 通知やロック画面に出すタイトル・チャンネル・サムネイル。
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.uploader)
            .setArtworkUri(item.thumbnailUrl?.let(Uri::parse))
            .build()
        // 失効したときに ChunkedDataSource が裏で取り直せるよう、元の動画と結び付けておく。
        YouTubeExtractor.registerStreamUrl(selection.videoUrl, item.url)
        selection.audioUrl?.let { YouTubeExtractor.registerStreamUrl(it, item.url) }
        val videoItem = MediaItem.Builder().setUri(selection.videoUrl).setMediaMetadata(metadata).build()
        val video = factory.createMediaSource(videoItem)
        val audioUrl = selection.audioUrl ?: return video
        return MergingMediaSource(video, factory.createMediaSource(MediaItem.fromUri(audioUrl)))
    }

    private fun applyOutputAndReload() {
        val selection = currentSelection
        val item = _state.value.current
        if (selection == null || item == null || player.playbackState == Player.STATE_IDLE) {
            applyOutput()
            return
        }
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        player.stop()
        prepare(item, selection, position, playWhenReady)
    }

    /** 車の Surface が優先。スマホの PlayerView は自分で SurfaceView を差し込むので、先に外しておく。 */
    private fun applyOutput() {
        val car = carSurface
        if (car != null) {
            phoneView?.player = null
            val aspect = carWidth.toFloat() / carHeight.coerceAtLeast(1)
            if (effectsDisabled) {
                setEffects(emptyList())
                player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            } else {
                setEffects(listOf(Presentation.createForAspectRatio(aspect, Presentation.LAYOUT_SCALE_TO_FIT), carLayout))
            }
            player.setVideoSurfaceHolder(FixedSurfaceHolder(car, carWidth, carHeight))
        } else {
            player.clearVideoSurface()
            setEffects(emptyList())
            phoneView?.player = player
        }
    }

    private fun setEffects(effects: List<Effect>) {
        try {
            player.setVideoEffects(effects)
            player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        } catch (e: Exception) {
            // エフェクトが使えない端末では、縦横比を保つ代わりに上下を切って全面に出す。
            AppLog.w(TAG, "video effects unavailable; cropping instead", e)
            player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    private fun playWhenReadyReasonName(reason: Int) = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
        else -> reason.toString()
    }

    private fun suppressionName(reason: Int) = when (reason) {
        Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "none"
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "TRANSIENT_AUDIO_FOCUS_LOSS"
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> "UNSUITABLE_AUDIO_OUTPUT"
        else -> reason.toString()
    }
}
