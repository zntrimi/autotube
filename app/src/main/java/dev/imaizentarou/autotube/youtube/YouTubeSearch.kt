package dev.imaizentarou.autotube.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.time.Duration
import java.time.Instant

/** 検索結果の 1 件。キューに積むのに要る情報（[meta]）に、一覧で見せる再生数・投稿日を足したもの。 */
data class SearchResult(
    val meta: VideoMeta,
    val viewCount: Long,
    val uploadedText: String?,
)

/** 続きのページを取るための手がかり。null なら最後のページ。 */
class SearchCursor internal constructor(internal val handler: SearchQueryHandler, internal val page: Page)

data class SearchPage(val results: List<SearchResult>, val next: SearchCursor?)

/**
 * アプリ内の YouTube 検索（NewPipeExtractor）。動画だけに絞り、ライブ配信は除く
 * （プログレッシブのストリームが無く再生できない）。
 */
object YouTubeSearch {
    private val service get() = ServiceList.YouTube

    suspend fun search(query: String): SearchPage = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val info = SearchInfo.getInfo(service, handler)
        SearchPage(info.relatedItems.toResults(), info.nextPage?.let { SearchCursor(handler, it) })
    }

    suspend fun more(cursor: SearchCursor): SearchPage = withContext(Dispatchers.IO) {
        val page = SearchInfo.getMoreItems(service, cursor.handler, cursor.page)
        SearchPage(page.items.toResults(), page.nextPage?.let { SearchCursor(cursor.handler, it) })
    }

    /** 入力中の候補。取れなければ空。 */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    // 24 時間配信などのライブは streamType が VIDEO_STREAM で来ることがあるので、長さが無いものも除く。
    private fun List<InfoItem>.toResults(): List<SearchResult> = filterIsInstance<StreamInfoItem>()
        .filter { it.streamType == StreamType.VIDEO_STREAM && it.duration > 0 }
        .mapNotNull { item ->
            val meta = YouTubeExtractor.metaOf(item) ?: return@mapNotNull null
            SearchResult(meta, item.viewCount, item.uploadDate?.instant?.let(::relativeJapanese))
        }

    /** YouTube の投稿日（英語の "3 weeks ago"）を日本語の「3週間前」に。 */
    private fun relativeJapanese(instant: Instant): String {
        val days = Duration.between(instant, Instant.now()).toDays()
        return when {
            days < 1 -> "今日"
            days < 7 -> "${days}日前"
            days < 30 -> "${days / 7}週間前"
            days < 365 -> "${days / 30}か月前"
            else -> "${days / 365}年前"
        }
    }
}
