package com.example.waterdetect.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 水量分布曲线图 —— 逐行移植小程序 pages/result/result.js 的 drawChart()：
 * 图框严格按 板宽:板高 物理比例；主网格横 50mm、纵每 10 管；红色水位曲线。
 */
class ChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var heights: List<Double?> = emptyList()
    private var tubeMax = 100        // 分格数 = 管数
    private var yMax = 280           // 满管高度(mm)
    private var boardWidthMm = 1200  // 板宽(mm)，用于图框长宽比

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#bbbbbb"); strokeWidth = 0.6f; style = Paint.Style.STROKE
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333"); strokeWidth = 1.2f; style = Paint.Style.STROKE
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333"); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e74c3c"); style = Paint.Style.STROKE
    }
    private val bgPaint = Paint().apply { color = Color.WHITE }

    fun setData(heights: List<Double?>, tubeMax: Int, yMax: Int, boardWidthMm: Int) {
        this.heights = heights
        this.tubeMax = tubeMax
        this.yMax = yMax
        this.boardWidthMm = boardWidthMm
        requestLayout()
        invalidate()
    }

    /** 高度由宽度按板宽:板高物理比例推出（与小程序 canvasWidth/canvasHeight 计算一致）。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(widthMeasureSpec)
            else -> 600
        }.coerceAtLeast(200)
        val minH = (140 * resources.displayMetrics.density).toInt()
        val h = max(minH, (w.toDouble() * yMax / boardWidthMm).roundToInt())
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = heights.size
        val W = width.toFloat()
        val H = height.toFloat()
        if (n == 0 || W <= 0 || H <= 0) return

        // 主格步长（与小程序一致：Y_MAX>=250 取 50；X 每 10 管一主格）
        val majorStep = if (yMax >= 250) 50 else max(20, (yMax / 6.0 / 10).roundToInt() * 10)
        val xMajorStep = if (tubeMax >= 80) 10 else 5

        val axisFont = max(10f, H * 0.075f)
        textPaint.textSize = axisFont

        val padLeft = (W * 0.10).roundToInt()
        val padRight = (W * 0.04).roundToInt()
        val originX = padLeft.toFloat()
        val pw = max(200f, W - padLeft - padRight)
        // 图框高度严格按 板高:板宽 物理比例
        val ph = max(60f, (pw * yMax / boardWidthMm.toDouble()).roundToInt().toFloat())

        val padTop = 14f
        val frameTop = padTop
        val originY = frameTop + ph

        val pxPerMm = ph / yMax
        val pxPerTube = pw / tubeMax

        // 白色背景
        canvas.drawRect(0f, 0f, W, H, bgPaint)

        // 主网格线（横向每 majorStep mm，纵向每 xMajorStep 管）
        val yDiv = yMax / majorStep
        val grid = android.graphics.Path()
        for (i in 0..yDiv) {
            val y = originY - i * majorStep * pxPerMm
            grid.moveTo(originX, y)
            grid.lineTo(originX + pw, y)
        }
        val xDiv = tubeMax / xMajorStep
        for (i in 0..xDiv) {
            val x = originX + i * xMajorStep * pxPerTube
            grid.moveTo(x, frameTop)
            grid.lineTo(x, originY)
        }
        canvas.drawPath(grid, gridPaint)

        // 图框矩形边框
        canvas.drawRect(originX, frameTop, originX + pw, originY, framePaint)

        // Y 轴刻度与标签（0, 50, ..., 300），刻度线向左
        val fontMetrics = textPaint.fontMetrics
        for (i in 0..yDiv) {
            val valMm = i * majorStep
            val y = originY - valMm * pxPerMm
            val label = valMm.toString()
            val tw = textPaint.measureText(label)
            val baseline = y - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText(label, originX - 5f - tw, baseline, textPaint)
            canvas.drawLine(originX - 4f, y, originX, y, tickPaint)
        }

        // X 轴刻度与标签（xMajorStep, 2*xMajorStep, ...），刻度线向下
        for (i in 1..xDiv) {
            val valTube = i * xMajorStep
            val x = originX + valTube * pxPerTube
            val label = valTube.toString()
            val tw = textPaint.measureText(label)
            canvas.drawText(label, x - tw / 2f, originY + 5f - fontMetrics.top, textPaint)
            canvas.drawLine(x, originY, x, originY + 4f, tickPaint)
        }

        // 水位曲线（红色细线；漏检管按 0 处理，与小程序 parseFloat(h)||0 一致）
        curvePaint.strokeWidth = max(0.8f, H * 0.004f)
        val curve = android.graphics.Path()
        for (i in 0 until n) {
            val x = originX + i * pxPerTube
            val y = originY - (heights[i] ?: 0.0) * pxPerMm
            if (i == 0) curve.moveTo(x, y.toFloat()) else curve.lineTo(x, y.toFloat())
        }
        canvas.drawPath(curve, curvePaint)
    }
}
