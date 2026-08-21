package com.nesstation.app.ui.emulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.nesstation.app.core.engine.NdsEngine

/**
 * NDS 双屏自由布局渲染视图（videoScale == "custom" 时使用）。
 *
 * 参照 melonDS 官方 Android 布局模型：上屏 / 下屏是两个独立组件，各占一个
 * 独立矩形，可分别缩放/移动。melonDS libretro 核心在 `melonds_screen_layout`
 * 选中布局内把两屏合成到**一个**帧缓冲；本视图不依赖 native blit，而是直接
 * 从 [NdsEngine.frameBuffer] 按布局切出上屏/下屏源区域，绘制到两个目标矩形。
 *
 * - 触摸：只有底部屏幕（触屏）矩形内触摸有效，坐标按下屏源区域在合成帧中的
 *   位置映射为 -0x8000..0x7FFF（libretro RETRO_DEVICE_POINTER 规范）。
 *   上屏内点击不触发触摸。
 * - 刷新：通过 Choreographer 在 VSync 上 invalidate()，与模拟线程同步取帧。
 */
class NdsDualScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 需要渲染的 NDS 引擎（可能随 GameSurfaceView 重建而重新注入）。 */
    var engine: NdsEngine? = null

    /** 核心当前合成布局："Top/Bottom" | "Bottom/Top" | "Left/Right" | "Right/Left" | "Top Only" | "Bottom Only"。 */
    var screenLayout: String = "Top/Bottom"

    /** 上屏目标矩形（归一化 0..1 [left, top, right, bottom]）。 */
    private val topRect = floatArrayOf(0.05f, 0.05f, 0.95f, 0.48f)

    /** 下屏目标矩形（归一化 0..1 [left, top, right, bottom]，也是触屏区域）。 */
    private val bottomRect = floatArrayOf(0.05f, 0.52f, 0.95f, 0.98f)

    /** UI 被菜单/设置等遮挡时，不消费触摸/按键。 */
    var uiBlocked: Boolean = false

    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var cacheBitmap: Bitmap? = null
    private var cacheW = 0
    private var cacheH = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isAttachedToWindow) {
                invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    /** 设置双屏目标矩形（归一化 0..1）。 */
    fun setRects(top: FloatArray, bottom: FloatArray) {
        setRectInto(topRect, top)
        setRectInto(bottomRect, bottom)
        invalidate()
    }

    fun getTopRect(): FloatArray = topRect.copyOf()
    fun getBottomRect(): FloatArray = bottomRect.copyOf()

    private fun setRectInto(dst: FloatArray, src: FloatArray) {
        dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2]; dst[3] = src[3]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val eng = engine ?: return
        if (!eng.isLoaded) return

        val vw = eng.videoWidth().coerceAtLeast(1)
        val vh = eng.videoHeight().coerceAtLeast(1)
        val fb = eng.frameBuffer
        if (fb.size < vw * vh) return

        if (cacheBitmap == null || cacheW != vw || cacheH != vh) {
            cacheBitmap = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            cacheW = vw
            cacheH = vh
        }
        val bmp = cacheBitmap ?: return
        bmp.setPixels(fb, 0, vw, 0, 0, vw, vh)

        val viewW = width.coerceAtLeast(1)
        val viewH = height.coerceAtLeast(1)

        // 按核心合成布局切出上屏/下屏源区域
        val (topSrc, bottomSrc) = computeSrcRects(vw, vh)

        if (topSrc != null) {
            val dst = rectToViewRect(topRect, viewW, viewH)
            val srcRect = Rect(
                topSrc.left.toInt(),
                topSrc.top.toInt(),
                topSrc.right.toInt(),
                topSrc.bottom.toInt()
            )
            canvas.drawBitmap(bmp, srcRect, dst, paint)
        }
        if (bottomSrc != null) {
            val dst = rectToViewRect(bottomRect, viewW, viewH)
            val srcRect = Rect(
                bottomSrc.left.toInt(),
                bottomSrc.top.toInt(),
                bottomSrc.right.toInt(),
                bottomSrc.bottom.toInt()
            )
            canvas.drawBitmap(bmp, srcRect, dst, paint)
        }
    }

    /**
     * 根据核心合成布局计算上屏/下屏在合成帧中的源矩形。
     *
     * 布局由 melonDS libretro core `melonds_screen_layout` 决定：
     *   Top/Bottom  → 合成帧 256x384，上屏在上半、下屏在下半
     *   Bottom/Top  → 合成帧 256x384，下屏在上半、上屏在下半
     *   Left/Right  → 合成帧 512x192，上屏在左半、下屏在右半
     *   Right/Left  → 合成帧 512x192，上屏在右半、下屏在左半
     *   Top Only    → 只有上屏（256x192）
     *   Bottom Only → 只有下屏（256x192）
     */
    private fun computeSrcRects(vw: Int, vh: Int): Pair<RectF?, RectF?> {
        val halfW = vw / 2
        val halfH = vh / 2
        return when (screenLayout) {
            "Bottom/Top" -> RectF(0f, halfH.toFloat(), vw.toFloat(), vh.toFloat()) to
                            RectF(0f, 0f, vw.toFloat(), halfH.toFloat())
            "Left/Right" -> RectF(0f, 0f, halfW.toFloat(), vh.toFloat()) to
                            RectF(halfW.toFloat(), 0f, vw.toFloat(), vh.toFloat())
            "Right/Left" -> RectF(halfW.toFloat(), 0f, vw.toFloat(), vh.toFloat()) to
                            RectF(0f, 0f, halfW.toFloat(), vh.toFloat())
            "Top Only" -> RectF(0f, 0f, vw.toFloat(), vh.toFloat()) to null
            "Bottom Only" -> null to RectF(0f, 0f, vw.toFloat(), vh.toFloat())
            else -> RectF(0f, 0f, vw.toFloat(), halfH.toFloat()) to
                    RectF(0f, halfH.toFloat(), vw.toFloat(), vh.toFloat())
        }
    }

    private fun rectToViewRect(rect: FloatArray, viewW: Int, viewH: Int): RectF =
        RectF(
            rect[0] * viewW,
            rect[1] * viewH,
            rect[2] * viewW,
            rect[3] * viewH
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (uiBlocked) return false
        val eng = engine as? NdsEngine ?: return false
        if (!eng.isLoaded) return false

        val vw = eng.videoWidth().coerceAtLeast(1)
        val vh = eng.videoHeight().coerceAtLeast(1)
        val viewW = width.coerceAtLeast(1)
        val viewH = height.coerceAtLeast(1)
        val (_topSrc, bottomSrc) = computeSrcRects(vw, vh)

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val bottomDst = rectToViewRect(bottomRect, viewW, viewH)
                val src = bottomSrc
                if (src != null && bottomDst.contains(event.x, event.y)) {
                    // 触摸点在下屏目标矩形内的归一化 uv
                    val t = ((event.x - bottomDst.left) / bottomDst.width()).toFloat().coerceIn(0f, 1f)
                    val s = ((event.y - bottomDst.top) / bottomDst.height()).toFloat().coerceIn(0f, 1f)
                    // 映射回合成帧中下屏源区域的坐标，再归一化为有符号 -0x8000..0x7FFF
                    val frameX = src.left + t * src.width()
                    val frameY = src.top + s * src.height()
                    val x16 = ((frameX / vw) * 0xFFFF - 0x8000).toInt().coerceIn(-0x8000, 0x7FFF)
                    val y16 = ((frameY / vh) * 0xFFFF - 0x8000).toInt().coerceIn(-0x8000, 0x7FFF)
                    eng.setTouchInput(x16, y16, true)
                } else {
                    // 点击不在触屏（下屏）区域 → 释放触摸
                    eng.setTouchInput(0, 0, false)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                eng.setTouchInput(0, 0, false)
                true
            }
            else -> false
        }
    }
}