package com.example.waterdetect.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.waterdetect.cv.CornerDetector
import kotlin.math.*

/**
 * 拍照/选图后的四角修正视图：
 * - 显示设备照片（按比例缩放）
 * - 四角圆点可拖拽修正
 * - 开启吸附后，拖动时放大镜下实时扫描红点并锁定（绿圈），松手坚定落点
 * - 双击某角回弹到自动检测位置
 */
class CornerEditView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class MutableCorner(var name: String, var color: String, var rx: Double, var ry: Double, var snapRx: Double, var snapRy: Double)

    private var bitmap: Bitmap? = null
    private var corners: MutableList<MutableCorner> = mutableListOf()
    private var scaleView = 1.0
    private var dispH = 0

    private var snapEnabled = true
    private var snapLevel = "mid"
    private var magZoom = 1

    private val SNAP_FRAC = mapOf("weak" to 0.45f, "mid" to 0.90f, "strong" to 1.0f)
    private val LOCK_STICK = 0.05

    private val density = resources.displayMetrics.density
    private val magR = 70f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.WHITE
    }
    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val amberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)

    private var draggingIdx = -1
    private var fingerView: PointF? = null
    private var centerView: PointF? = null
    private var snapActive = false
    private var lastSnapNorm: Pair<Double, Double>? = null
    private var lastDownTime = 0L
    private var lastDownIdx = -1

    fun setData(bmp: Bitmap, pts: List<CornerDetector.CornerPoint>) {
        bitmap = bmp
        corners = pts.map { MutableCorner(it.name, it.color, it.rx, it.ry, it.snap.first, it.snap.second) }.toMutableList()
        requestLayout()
        invalidate()
    }

    fun setSnapEnabled(on: Boolean) { snapEnabled = on; invalidate() }
    fun setMagZoom(z: Int) { magZoom = max(1, z); invalidate() }
    fun setSnapLevel(level: String) { snapLevel = level; invalidate() }

    fun getCorners(): List<CornerDetector.CornerPoint> =
        corners.map { CornerDetector.CornerPoint(it.name, it.color, it.rx, it.ry, it.rx to it.ry) }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val bmp = bitmap
        if (bmp != null && bmp.width > 0) {
            dispH = (w * bmp.height / bmp.width.toDouble()).toInt()
            scaleView = w.toDouble() / bmp.width
            setMeasuredDimension(w, dispH)
        } else {
            setMeasuredDimension(w, 0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, null, Rect(0, 0, width, dispH), paint)

        for (c in corners) {
            val cx = (c.rx * width).toFloat()
            val cy = (c.ry * dispH).toFloat()
            val col = Color.parseColor(c.color)
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, 14f * density, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * density; paint.color = col
            canvas.drawCircle(cx, cy, 14f * density, paint)
            labelBg.color = col
            canvas.drawCircle(cx, cy, 5f * density, labelBg)
        }

        if (draggingIdx >= 0 && fingerView != null && snapEnabled) {
            drawMagnifier(canvas)
        }
    }

    private fun drawMagnifier(canvas: Canvas) {
        val fv = fingerView ?: return
        val bmp = bitmap ?: return
        canvas.save()
        val path = Path(); path.addCircle(fv.x, fv.y, magR, Path.Direction.CW)
        canvas.clipPath(path)
        val srcSize = (2 * magR / magZoom) / scaleView
        val ox = fv.x / scaleView; val oy = fv.y / scaleView
        val src = RectF(
            (ox - srcSize / 2).toFloat(), (oy - srcSize / 2).toFloat(),
            (ox + srcSize / 2).toFloat(), (oy + srcSize / 2).toFloat()
        )
        val dst = RectF(fv.x - magR, fv.y - magR, fv.x + magR, fv.y + magR)
        canvas.drawBitmap(bmp, src, dst, paint)
        canvas.restore()
        canvas.drawCircle(fv.x, fv.y, magR, borderPaint)

        centerView?.let { cv ->
            canvas.drawCircle(cv.x, cv.y, 10f * density, if (snapActive) greenPaint else amberPaint)
        }
    }

    private fun scanRedCenter(ox: Float, oy: Float, radiusPx: Int): PointF? {
        val bmp = bitmap ?: return null
        val cx0 = max(0, (ox - radiusPx).toInt())
        val cy0 = max(0, (oy - radiusPx).toInt())
        val cx1 = min(bmp.width, (ox + radiusPx).toInt())
        val cy1 = min(bmp.height, (oy + radiusPx).toInt())
        if (cx1 <= cx0 || cy1 <= cy0) return null
        val w = cx1 - cx0; val h = cy1 - cy0
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, cx0, cy0, w, h)
        var sx = 0.0; var sy = 0.0; var count = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r > 150 && g < 100 && b < 100) {
                sx += (i % w); sy += (i / w); count++
            }
        }
        if (count < 5) return null
        return PointF(cx0 + (sx / count).toFloat(), cy0 + (sy / count).toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || corners.isEmpty()) return false
        val bmp = bitmap!!
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val idx = nearestCorner(event.x, event.y)
                if (idx < 0) return false
                val now = System.currentTimeMillis()
                if (now - lastDownTime < 300 && idx == lastDownIdx) {
                    val c = corners[idx]; c.rx = c.snapRx; c.ry = c.snapRy
                    invalidate(); return true
                }
                lastDownTime = now; lastDownIdx = idx
                draggingIdx = idx
                fingerView = PointF(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingIdx < 0) return true
                val ox = (event.x / scaleView).toFloat()
                val oy = (event.y / scaleView).toFloat()
                val fingerNorm = ox / bmp.width to oy / bmp.height
                var target = fingerNorm
                centerView = null; snapActive = false
                if (snapEnabled) {
                    val scanR = ((magR / magZoom) / scaleView).toInt().coerceAtLeast(4)
                    val center = scanRedCenter(ox, oy, scanR)
                    if (center != null) {
                        val d = hypot(center.x - ox, center.y - oy)
                        val snapR = scanR * (SNAP_FRAC[snapLevel] ?: 0.9f)
                        if (d < snapR) {
                            target = center.x / bmp.width to center.y / bmp.height
                            centerView = PointF((center.x * scaleView).toFloat(), (center.y * scaleView).toFloat())
                            snapActive = true
                            lastSnapNorm = target
                        } else {
                            lastSnapNorm = null
                        }
                    } else {
                        lastSnapNorm = null
                    }
                } else {
                    lastSnapNorm = null
                }
                val c = corners[draggingIdx]
                c.rx = target.first; c.ry = target.second
                fingerView = PointF(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingIdx >= 0 && snapEnabled && lastSnapNorm != null) {
                    val c = corners[draggingIdx]
                    val cur = c.rx to c.ry
                    if (hypot(cur.first - lastSnapNorm!!.first, cur.second - lastSnapNorm!!.second) < LOCK_STICK) {
                        c.rx = lastSnapNorm!!.first; c.ry = lastSnapNorm!!.second
                    }
                }
                draggingIdx = -1
                fingerView = null; centerView = null; snapActive = false
                invalidate()
                return true
            }
        }
        return false
    }

    private fun nearestCorner(vx: Float, vy: Float): Int {
        val hitR = 30f * density
        var best = -1; var bestD = hitR
        for (i in corners.indices) {
            val cx = (corners[i].rx * width).toFloat()
            val cy = (corners[i].ry * dispH).toFloat()
            val d = hypot(cx - vx, cy - vy)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
