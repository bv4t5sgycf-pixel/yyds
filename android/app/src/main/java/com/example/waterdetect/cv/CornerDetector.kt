package com.example.waterdetect.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.utils.MatVector
import kotlin.math.hypot

object CornerDetector {

    private const val SNAP_FRAC_WEAK = 0.45
    private const val SNAP_FRAC_MID = 0.90
    private const val SNAP_FRAC_STRONG = 1.0
    private const val LOCK_STICK = 0.05

    data class DetectedCorner(
        val name: String,
        val rx: Double,
        val ry: Double,
        val color: String
    )

    data class Result(
        val success: Boolean,
        val corners: List<DetectedCorner>,
        val detectedCount: Int,
        val fallback: Boolean
    )

    fun detectRedCorners(bitmap: Bitmap, boardType: String): Result {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val hsv = Mat()
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_RGB2HSV)

        val red1 = Mat()
        val red2 = Mat()
        Core.inRange(
            hsv,
            Scalar(0.0, 80.0, 50.0),
            Scalar(10.0, 255.0, 255.0),
            red1
        )
        Core.inRange(
            hsv,
            Scalar(160.0, 80.0, 50.0),
            Scalar(180.0, 255.0, 255.0),
            red2
        )

        val redMask = Mat()
        Core.bitwise_or(red1, red2, redMask)
        red1.release()
        red2.release()
        hsv.release()

        Imgproc.morphologyEx(
            redMask, redMask, Imgproc.MORPH_CLOSE,
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        )

        val contours = MatVector()
        Imgproc.findContours(
            redMask, contours, Mat(),
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )
        redMask.release()

        val candidates = mutableListOf<Triple<Point, Double, Double>>()
        val totalArea = (src.cols() * src.rows()).toDouble()

        for (i in 0 until contours.size()) {
            val c = contours[i]
            val area = Imgproc.contourArea(c)
            if (area < totalArea * 0.0003) continue

            val perim = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
            if (perim <= 0) continue
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(
                MatOfPoint2f(*c.toArray()), approx,
                0.04 * perim, true
            )
            val vertices = approx.toArray().size
            approx.release()

            val circularity = if (perim > 0) {
                4 * Math.PI * area / (perim * perim)
            } else 0.0
            val score = if (vertices in 4..8) circularity * 1.3 else circularity

            if (score > 0.45) {
                val m = Imgproc.moments(c)
                if (m.m00 > 0) {
                    val cx = m.m10 / m.m00
                    val cy = m.m01 / m.m00
                    candidates.add(Triple(Point(cx, cy), score, area))
                }
            }
            c.release()
        }
        contours.release()
        src.release()

        val anchor = defaultAnchors[boardType] ?: defaultAnchors["Board1200"]!!
        val ordered = orderToCorners(candidates.map { it.first }, anchor)

        val result = ordered.mapIndexed { index, pt ->
            val name = when (index) {
                0 -> "左上"
                1 -> "右上"
                2 -> "左下"
                3 -> "右下"
                else -> "角点${index + 1}"
            }
            DetectedCorner(name, pt.x, pt.y, "red")
        }

        return Result(
            success = result.size == 4,
            corners = result,
            detectedCount = result.size,
            fallback = result.size != 4
        )
    }

    private val defaultAnchors = mapOf(
        "Board1200" to listOf(
            Point(0.10, 0.40), Point(0.85, 0.40),
            Point(0.10, 0.62), Point(0.85, 0.62)
        ),
        "Board1008" to listOf(
            Point(0.10, 0.40), Point(0.85, 0.40),
            Point(0.10, 0.62), Point(0.85, 0.62)
        ),
        "Board4000" to listOf(
            Point(0.03, 0.20), Point(0.97, 0.20),
            Point(0.03, 0.75), Point(0.97, 0.75)
        )
    )

    private fun orderToCorners(
        points: List<Point>,
        anchors: List<Point>
    ): List<Point> {
        if (points.isEmpty()) return anchors
        val used = BooleanArray(points.size) { false }
        return anchors.map { anchor ->
            var best = -1
            var bestDist = Double.MAX_VALUE
            for (i in points.indices) {
                if (used[i]) continue
                val d = hypot(points[i].x - anchor.x, points[i].y - anchor.y)
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            used[best] = true
            points[best]
        }
    }

    fun snapCorner(
        rawX: Float, rawY: Float,
        w: Int, h: Int,
        corners: List<DetectedCorner>,
        boardType: String
    ): Pair<Float, Float> {
        val anchor = defaultAnchors[boardType] ?: defaultAnchors["Board1200"]!!
        val pts = corners.map { Point(it.rx * w, it.ry * h) }

        val cp = closestPointOnQuad(rawX, rawY, pts)
        val dx = cp.first - rawX
        val dy = cp.second - rawY
        val dist = hypot(dx.toDouble(), dy.toDouble())
        val maxSnap = hypot(w.toDouble(), h.toDouble()) * 0.12

        val snapFrac = when {
            dist < maxSnap * 0.35 -> SNAP_FRAC_STRONG
            dist < maxSnap * 0.70 -> SNAP_FRAC_MID
            dist < maxSnap -> SNAP_FRAC_WEAK
            else -> 0.0
        }

        return Pair(
            rawX + (dx * snapFrac).toFloat(),
            rawY + (dy * snapFrac).toFloat()
        )
    }

    private fun closestPointOnQuad(
        x: Float, y: Float,
        pts: List<Point>
    ): Pair<Float, Float> {
        if (pts.size < 4) return Pair(x, y)
        val edges = listOf(0 to 1, 1 to 3, 3 to 2, 2 to 0)
        var bestX = x.toDouble()
        var bestY = y.toDouble()
        var bestD = Double.MAX_VALUE

        for ((a, b) in edges) {
            val p0 = pts[a]
            val p1 = pts[b]
            val dx = p1.x - p0.x
            val dy = p1.y - p0.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else
                ((x - p0.x) * dx + (y - p0.y) * dy) / len2
            val clamped = t.coerceIn(0.0, 1.0)
            val px = p0.x + clamped * dx
            val py = p0.y + clamped * dy
            val d = hypot(px - x, py - y)
            if (d < bestD) {
                bestD = d
                bestX = px
                bestY = py
            }
        }
        return Pair(bestX.toFloat(), bestY.toFloat())
    }
}
