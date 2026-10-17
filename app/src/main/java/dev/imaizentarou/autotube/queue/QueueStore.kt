package dev.imaizentarou.autotube.queue

import android.content.Context
import dev.imaizentarou.autotube.youtube.VideoMeta
import dev.imaizentarou.autotube.youtube.YouTubeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class QueueItem(
    val id: String,
    val url: String,
    val videoId: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
    /** 前回止めた位置。続きから再生するために持つ。 */
    val positionMs: Long = 0,
    /** 最後まで見た。自動送りではスキップし、一覧では ✓ を付ける。 */
    val watched: Boolean = false,
    /** 削除・非公開などで再生できなかった。自動送りでは飛ばす。手で再生して映れば外れる。 */
    val unavailable: Boolean = false,
) {
    /** 自動送り・乗ってすぐの自動再生の対象か。 */
    val playable: Boolean get() = !watched && !unavailable

    val durationLabel: String get() = formatSeconds(durationSec)

    /** 続きから再生すべき位置。冒頭や末尾ぎりぎりなら 0（最初から）。 */
    val resumePositionMs: Long
        get() {
            val durationMs = durationSec * 1000
            return if (positionMs > 5_000 && (durationMs <= 0 || positionMs < durationMs - 10_000)) positionMs else 0
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("videoId", videoId)
        put("title", title)
        put("uploader", uploader)
        put("durationSec", durationSec)
        put("thumbnailUrl", thumbnailUrl ?: JSONObject.NULL)
        put("positionMs", positionMs)
        put("watched", watched)
        put("unavailable", unavailable)
    }

    companion object {
        fun fromJson(json: JSONObject) = QueueItem(
            id = json.getString("id"),
            url = json.getString("url"),
            videoId = json.optString("videoId"),
            title = json.optString("title"),
            uploader = json.optString("uploader"),
            durationSec = json.optLong("durationSec", 0L),
            thumbnailUrl = if (json.isNull("thumbnailUrl")) null else json.optString("thumbnailUrl"),
            positionMs = json.optLong("positionMs", 0L),
            watched = json.optBoolean("watched", false),
            unavailable = json.optBoolean("unavailable", false),
        )

        fun fromMeta(meta: VideoMeta) = QueueItem(
            id = UUID.randomUUID().toString(),
            url = meta.url,
            videoId = meta.videoId,
            title = meta.title,
            uploader = meta.uploader,
            durationSec = meta.durationSec,
            thumbnailUrl = meta.thumbnailUrl,
        )

        fun formatSeconds(seconds: Long): String {
            if (seconds <= 0) return ""
            val minutes = seconds / 60
            val rest = seconds % 60
            return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, rest) else "%d:%02d".format(minutes, rest)
        }
    }
}

/** 追加結果。再生リストなら複数件になる。 */
data class AddResult(val added: List<QueueItem>, val skipped: Int)

/** スマホから積む再生キュー。`filesDir/queue.json` に保存し、車側・スマホ側の両方が同じ StateFlow を見る。 */
object QueueStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var file: File
    private val saveMutex = Mutex()

    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    fun initialize(context: Context) {
        file = File(context.applicationContext.filesDir, "queue.json")
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { load() }
            _items.update { current -> if (current.isEmpty()) loaded else current }
        }
    }

    fun find(id: String?): QueueItem? = id?.let { key -> _items.value.firstOrNull { it.id == key } }

    /** 自動送りの次。current の後ろで最初の未視聴。無ければ null（巻き戻さない）。 */
    fun nextUnwatchedAfter(currentId: String?): QueueItem? {
        val list = _items.value
        val index = list.indexOfFirst { it.id == currentId }
        return list.drop(index + 1).firstOrNull { it.playable }
    }

    fun previousOf(currentId: String?): QueueItem? {
        val list = _items.value
        val index = list.indexOfFirst { it.id == currentId }
        return list.getOrNull(index - 1)
    }

    /** 乗ってすぐ再生する候補。前回の動画が残っていればそれ、無ければ最初の未視聴。 */
    fun autoPlayCandidate(lastPlayedId: String?): QueueItem? =
        find(lastPlayedId)?.takeIf { it.playable } ?: _items.value.firstOrNull { it.playable }

    /**
     * URL か共有テキストから追加する。再生リストの URL（`playlist?list=`）なら全件、
     * 動画 URL なら 1 件。動画は情報取得（タイトル・長さ）をしてから積む。同じ動画は二重に積まない。
     */
    suspend fun add(input: String): Result<AddResult> {
        YouTubeExtractor.playlistUrl(input)?.let { return addPlaylist(it) }
        val url = YouTubeExtractor.canonicalUrl(input)
            ?: return Result.failure(IllegalArgumentException("YouTube の動画・再生リストの URL ではありません"))
        val videoId = YouTubeExtractor.videoId(url)
        _items.value.firstOrNull { it.videoId == videoId }?.let {
            return Result.success(AddResult(emptyList(), skipped = 1))
        }
        return runCatching {
            val item = QueueItem.fromMeta(YouTubeExtractor.meta(url))
            _items.update { it + item }
            persist()
            AddResult(listOf(item), skipped = 0)
        }
    }

    /**
     * 検索結果など、情報がもう揃っているものをそのまま積む。同じ動画が既にあれば何もせず null。
     * [afterId] を渡すとその直後（「次に再生」）、null なら末尾。
     */
    fun addMeta(meta: VideoMeta, afterId: String? = null, playNext: Boolean = false): QueueItem? {
        if (_items.value.any { it.videoId == meta.videoId }) return null
        val item = QueueItem.fromMeta(meta)
        _items.update { list ->
            if (!playNext) return@update list + item
            val index = list.indexOfFirst { it.id == afterId }
            list.toMutableList().apply { add(index + 1, item) }
        }
        persist()
        return item
    }

    /** 削除の取り消し用。元の位置に戻す。 */
    fun insert(item: QueueItem, index: Int) {
        _items.update { list ->
            if (list.any { it.id == item.id }) return@update list
            list.toMutableList().apply { add(index.coerceIn(0, size), item) }
        }
        persist()
    }

    /** つまんで並べ替え。id 同士で指定する（表示は視聴済みを分けているので添字が一致しない）。 */
    fun move(id: String, beforeOrAfterId: String, after: Boolean) {
        _items.update { list ->
            val item = list.firstOrNull { it.id == id } ?: return@update list
            val rest = list.filterNot { it.id == id }.toMutableList()
            val target = rest.indexOfFirst { it.id == beforeOrAfterId }
            if (target < 0) return@update list
            rest.add(if (after) target + 1 else target, item)
            rest
        }
        persist()
    }

    fun contains(videoId: String): Boolean = _items.value.any { it.videoId == videoId }

    private suspend fun addPlaylist(url: String): Result<AddResult> = runCatching {
        val metas = YouTubeExtractor.playlist(url)
        val known = _items.value.map { it.videoId }.toMutableSet()
        val added = metas.filter { known.add(it.videoId) }.map { QueueItem.fromMeta(it) }
        if (added.isNotEmpty()) {
            _items.update { it + added }
            persist()
        }
        AddResult(added, skipped = metas.size - added.size)
    }

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun removeWatched() {
        _items.update { list -> list.filterNot { it.watched } }
        persist()
    }

    fun moveToTop(id: String) {
        _items.update { list ->
            val item = list.firstOrNull { it.id == id } ?: return@update list
            listOf(item) + list.filterNot { it.id == id }
        }
        persist()
    }

    /** 「次に再生」。今の動画の直後に差し込む。再生中の動画が無ければ先頭。 */
    fun moveAfter(id: String, currentId: String?) {
        _items.update { list ->
            val item = list.firstOrNull { it.id == id } ?: return@update list
            val rest = list.filterNot { it.id == id }
            val index = rest.indexOfFirst { it.id == currentId }
            rest.toMutableList().apply { add(index + 1, item) }
        }
        persist()
    }

    fun update(id: String, transform: (QueueItem) -> QueueItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
        persist()
    }

    /** 一括削除の取り消し用。 */
    fun replaceAll(items: List<QueueItem>) {
        _items.value = items
        persist()
    }

    fun clear() {
        _items.value = emptyList()
        persist()
    }

    private fun persist() {
        val snapshot = _items.value
        scope.launch {
            saveMutex.withLock {
                withContext(Dispatchers.IO) {
                    val array = JSONArray()
                    snapshot.forEach { array.put(it.toJson()) }
                    val temporary = File(file.parentFile, "${file.name}.tmp")
                    temporary.writeText(array.toString())
                    temporary.renameTo(file)
                }
            }
        }
    }

    private fun load(): List<QueueItem> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            List(array.length()) { QueueItem.fromJson(array.getJSONObject(it)) }
        } catch (_: JSONException) {
            emptyList()
        }
    }
}
