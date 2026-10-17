package dev.imaizentarou.autotube.player

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder

/**
 * 生の Surface に大きさを添えて ExoPlayer に渡すための SurfaceHolder。
 * `setVideoSurface(Surface)` だと出力解像度が不明扱いになり、エフェクト（レターボックス）を
 * 使うときに描画先の大きさが決まらない。`setVideoSurfaceHolder` なら getSurfaceFrame() から取ってくれる。
 */
class FixedSurfaceHolder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
) : SurfaceHolder {
    override fun getSurface(): Surface = surface
    override fun getSurfaceFrame(): Rect = Rect(0, 0, width, height)
    override fun isCreating(): Boolean = false

    override fun addCallback(callback: SurfaceHolder.Callback) {
        // 大きさは固定で、破棄は CarAppService 側の SurfaceCallback で扱うので通知するものが無い。
    }

    override fun removeCallback(callback: SurfaceHolder.Callback) {}

    @Deprecated("Deprecated in Java")
    override fun setType(type: Int) {}
    override fun setFixedSize(width: Int, height: Int) {}
    override fun setSizeFromLayout() {}
    override fun setFormat(format: Int) {}
    override fun setKeepScreenOn(screenOn: Boolean) {}
    override fun lockCanvas(): Canvas? = null
    override fun lockCanvas(dirty: Rect?): Canvas? = null
    override fun unlockCanvasAndPost(canvas: Canvas) {}
}
