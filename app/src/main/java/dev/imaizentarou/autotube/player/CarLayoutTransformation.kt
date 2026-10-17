package dev.imaizentarou.autotube.player

import android.graphics.Matrix
import android.graphics.Rect
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation

/**
 * 車の Surface のうち、ホストが「見えている」と言う領域に映像を収める。
 *
 * 前段の Presentation で映像は Surface と同じ縦横比のフレームに（黒帯付きで）収まっている。
 * ここではそのフレーム全体を NDC 座標で一様に縮め、可視領域の中心へ動かす。
 * 全画面のときは何もしない（ボタンは映像の上に重なるだけ）。Coolwalk の分割表示では
 * 右側のカードだけが見えるので、そこに最大サイズで置く。
 *
 * getMatrix はフレームごとに GL スレッドから呼ばれるので、状態を差し替えれば次のフレームから効く。
 */
@OptIn(UnstableApi::class)
class CarLayoutTransformation : MatrixTransformation {
    @Volatile var surfaceWidth = 0
    @Volatile var surfaceHeight = 0
    @Volatile var visibleArea: Rect? = null
    /** 映像の縦横比。分かるまでは 16:9 とみなす。 */
    @Volatile var videoAspect = 16f / 9f

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val matrix = Matrix()
        val surfaceW = surfaceWidth.toFloat()
        val surfaceH = surfaceHeight.toFloat()
        val area = visibleArea ?: return matrix
        if (surfaceW <= 0 || surfaceH <= 0 || area.isEmpty) return matrix
        // 幅の 8 割以上見えているなら全画面扱い。ボタンの出入りで映像が動かないようにする。
        if (area.width() >= surfaceW * FULLSCREEN_THRESHOLD) return matrix

        // Presentation 後のフレーム（= Surface と同じ比率）の中で映像が占める大きさ。
        val frameAspect = surfaceW / surfaceH
        val videoW = if (videoAspect >= frameAspect) surfaceW else surfaceH * videoAspect
        // 可視領域に収まる最大の映像の大きさ。
        val areaAspect = area.width().toFloat() / area.height()
        val targetW = if (videoAspect >= areaAspect) area.width().toFloat() else area.height() * videoAspect

        val scale = targetW / videoW
        val centerX = area.exactCenterX() / surfaceW * 2f - 1f
        val centerY = 1f - area.exactCenterY() / surfaceH * 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(centerX, centerY)
        return matrix
    }

    private companion object {
        const val FULLSCREEN_THRESHOLD = 0.8f
    }
}
