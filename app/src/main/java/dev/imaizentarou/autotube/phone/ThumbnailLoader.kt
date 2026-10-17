package dev.imaizentarou.autotube.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 一覧のサムネイル用。ライブラリを入れるほどでもないので、メモリキャッシュ付きの素朴な読み込み。 */
object ThumbnailLoader {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { fetch(url) } ?: return null
        cache.put(url, bitmap)
        return bitmap
    }

    private fun fetch(url: String): Bitmap? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            connection.inputStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
