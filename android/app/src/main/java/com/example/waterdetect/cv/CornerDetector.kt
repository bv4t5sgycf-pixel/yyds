package com.example.waterdetect.cv

import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import kotlin.math.*

/**
 * 四角红点自动检测（Kotlin 翻译自 PWA corners.js）。
 * 输入为已转换的 BGR Mat（原图尺寸），输出归一化四角位置。
 * 作为初始定位，用户仍可用放大镜吸附手动精确修正。
 */
object CornerDetector {

    private data class DefaultCorner(val name: String, val color: String, val rx: Double, val ry: Double)

    private val DEFAULT_CORNERS: Map<String, List<DefaultCorner>> = mapOf(
        "Board1008" to listOf(
            DefaultCorner("TL", "#e74c3c", 0.14, 0.16),
            DefaultCorner("TR", "#2ecc71", 0.86, 0.16),
            DefaultCorner("BL", "#3498db", 0.14, 0.84),
            DefaultCorner("BR", "#f39c12", 0.86, 0.84)
        ),
        "Board1200" to listOf(
            DefaultCorner("TL", "#e74c3c", 0.14, 0.16),
            DefaultCorner("TR", "#2ecc71", 0.86, 0.16),
            DefaultCorner("BL", "#3498db", 0.14, 0.84),
            DefaultCorner("BR", "#f39c12", 0.86, 0.84)
        ),
        "Board4000" to listOf(
            DefaultCorner("TL", "#e74c3c", 0.14, 0.16),
            DefaultCorner("TR", "#2ecc71", 0.86, 0.16),
            DefaultCorner("BL", "#3498db", 0.14, 0.84),
            DefaultCorner("BR", "#f39c12", 0.86, 0.84)
        )
    )

    data class CornerPoint(
        val name: String,
        val color: String,
        val rx: Double,
        val ry: Double,
        val snap: Pair<Double, Double>
    )

    data class CornerDetectResult(
        val success: Boolean,
        val cornerPoints: List<CornerPoint>,
        val detectedCount: Int,
        val fallback: Boolean
    )

    private fun round4(x: Double): Double = Math.round(x * 10000) / 10000.0

    private data class Cand(val nx: Double, val ny: Double, val area: Double)

    private fun pickCorners(cands: List<Cand>, defaults: List<DefaultCorner>): List<CornerPoint> {
        val result = mutableListOf<CornerPoint>()
        val used = mutableSetOf<Int>()
        for (d in defaults) {
            var best = -1
            var bestD = Double.MAX_VALUE
            for (c in cands.indices) {
                if (used.contains(c)) continue
                val dist = Math.hypot(cands[c].nx - d.rx, cands[c].ny - d.ry)
                if (dist < bestD) { bestD = dist; best = c }
            }
            if (best != -1 && bestD < 0.32) {
                val cc = cands[best]
                used.add(best)
                val nx = round4(cc.nx); val ny = round4(cc.ny)
                result.add(CornerPoint(d.name, d.color, nx, ny, nx to ny))
            } else {
                result.add(CornerPoint(d.name, d.color, d.rx, d.ry, d.rx to d.ry))
            }
        }
        return result
    }

    fun detectRedCorners(mat: Mat, boardType: String = "Board1200"): CornerDetectResult {
        val origW = mat.cols().toDouble()
        val origH = mat.rows().toDouble()

        val maxSide = 1000.0
        val scale = min(1.0, maxSide / max(mat.cols(), mat.rows()))
        val small = Mat()
        if (scale < 1.0) {
            opencv_imgproc.resize(mat, small, Size(Math.round(mat.cols() * scale).toInt(), Math.round(mat.rows() * scale).toInt()), 0.0, 0.0, opencv_imgproc.INTER_AREA)
        } else {
            mat.copyTo(small)
        }

        val hsv = Mat()
        opencv_imgproc.cvtColor(small, hsv, opencv_imgproc.COLOR_BGR2HSV)

        // bytedeco inRange 的上下界必须是 Mat（用 1x1 CV_8UC3 承载 HSV 标量阈值）
        val m1 = Mat(); val m2 = Mat(); val mask = Mat()
        val lo1 = Mat(Size(1, 1), opencv_core.CV_8UC(3), Scalar(0.0, 45.0, 40.0, 0.0))
        val hi1 = Mat(Size(1, 1), opencv_core.CV_8UC(3), Scalar(18.0, 255.0, 255.0, 0.0))
        val lo2 = Mat(Size(1, 1), opencv_core.CV_8UC(3), Scalar(152.0, 40.0, 40.0, 0.0))
        val hi2 = Mat(Size(1, 1), opencv_core.CV_8UC(3), Scalar(180.0, 255.0, 255.0, 0.0))
        opencv_core.inRange(hsv, lo1, hi1, m1)
        opencv_core.inRange(hsv, lo2, hi2, m2)
        opencv_core.bitwise_or(m1, m2, mask)

        val kernel = opencv_imgproc.getStructuringElement(opencv_imgproc.MORPH_ELLIPSE, Size(3, 3))
        val opened = Mat()
        opencv_imgproc.morphologyEx(mask, opened, opencv_imgproc.MORPH_OPEN, kernel)

        val contours = MatVector()
        val hier = Mat()
        opencv_imgproc.findContours(opened, contours, hier, opencv_imgproc.RETR_EXTERNAL, opencv_imgproc.CHAIN_APPROX_SIMPLE)

        val minArea = small.cols() * small.rows() * 0.00008
        val cands = mutableListOf<Cand>()
        for (k in 0 until contours.size().toInt()) {
            val cnt = contours.get(k.toLong())
            val area = opencv_imgproc.contourArea(cnt)
            if (area < minArea) continue
            val rect = opencv_imgproc.boundingRect(cnt)
            val bw = rect.width(); val bh = rect.height()
            if (bw < 1 || bh < 1) continue
            val aspect = max(bw, bh).toDouble() / (min(bw, bh) + 1e-6)
            if (aspect > 4.0) continue
            val peri = opencv_imgproc.arcLength(cnt, true)
            val circ = if (peri > 0) (4 * Math.PI * area / (peri * peri + 1e-6)) else 0.0
            if (circ < 0.25) continue
            val mom = opencv_imgproc.moments(cnt)
            if (mom.m00() == 0.0) continue
            val cx = mom.m10() / mom.m00()
            val cy = mom.m01() / mom.m00()
            val ox = cx / scale
            val oy = cy / scale
            cands.add(Cand(ox / origW, oy / origH, area))
        }

        val defaults = DEFAULT_CORNERS[boardType] ?: DEFAULT_CORNERS["Board1200"]!!
        val cornerPoints = pickCorners(cands, defaults)

        val detectedCount = cornerPoints.count { p ->
            defaults.none { d -> Math.abs(p.rx - d.rx) <= 1e-4 && Math.abs(p.ry - d.ry) <= 1e-4 }
        }

        listOf(small, hsv, m1, m2, mask, kernel, opened, hier, lo1, hi1, lo2, hi2).forEach { it.release() }
        contours.deallocate()

        return CornerDetectResult(
            success = detectedCount >= 3,
            cornerPoints = cornerPoints,
            detectedCount = detectedCount,
            fallback = detectedCount < 4
        )
    }
}
