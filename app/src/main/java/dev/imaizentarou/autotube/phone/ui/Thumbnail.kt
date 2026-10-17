package dev.imaizentarou.autotube.phone.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.imaizentarou.autotube.phone.ThumbnailLoader

/** 角丸のサムネイル。読み込み中は面の色だけ。上に重ねたいもの（長さ・進み具合）は [overlay] で。 */
@Composable
fun Thumbnail(url: String?, modifier: Modifier = Modifier, overlay: @Composable BoxScope.() -> Unit = {}) {
    var bitmap by remember(url) { mutableStateOf(url?.let(ThumbnailLoader::cached)) }
    LaunchedEffect(url) {
        if (url != null && bitmap == null) bitmap = ThumbnailLoader.load(url)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        overlay()
    }
}

/** 動画 ID だけ分かっているとき（クリップボードの URL など）のサムネイル URL。 */
fun thumbnailUrlOf(videoId: String) = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
