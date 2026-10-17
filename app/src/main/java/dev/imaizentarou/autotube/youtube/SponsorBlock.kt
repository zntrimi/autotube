package dev.imaizentarou.autotube.youtube

import dev.imaizentarou.autotube.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** 飛ばす区間。秒。 */
data class SkipSegment(val startSec: Double, val endSec: Double, val category: String)

/**
 * SponsorBlock（https://sponsor.ajay.app）。有志がタグ付けした提供スポンサー枠・宣伝・
 * 「チャンネル登録お願いします」区間を取ってきて、再生中に飛ばす。
 * 動画 ID をこのサーバーへ送ることになる。
 */
object SponsorBlock {
    private const val TAG = "AutoTube.SponsorBlock"
    private val categories = listOf("sponsor", "selfpromo", "interaction")
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun segments(videoId: String): List<SkipSegment> = withContext(Dispatchers.IO) {
        val categoriesJson = JSONArray(categories).toString()
        val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$categoriesJson"
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                // 404 は「登録なし」。
                if (response.code == 404) return@withContext emptyList()
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "HTTP ${response.code} for $videoId")
                    return@withContext emptyList()
                }
                val array = JSONArray(response.body.string())
                val result = ArrayList<SkipSegment>()
                for (index in 0 until array.length()) {
                    val entry = array.getJSONObject(index)
                    val segment = entry.getJSONArray("segment")
                    result += SkipSegment(segment.getDouble(0), segment.getDouble(1), entry.optString("category"))
                }
                result.sortedBy { it.startSec }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "lookup failed for $videoId: ${e.message}")
            emptyList()
        }
    }
}
