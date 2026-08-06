package com.example.waterdetect.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
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
import kotlin.math.pow
import kotlin.math.sqrt

object WaterDetector {

    data class Ball(
        val x: Double,
        val y: Double,
        val radius: Double,
        val area: Double
    )

    data class Result(
        val success: Boolean,
        val count: Int,
        val totalVolumeMl: Double,
        val balls: List<Ball>,
        val smoothness: Double,
        val successRate: Double
    )

    fun detect(bitmap: Bitmap, boardType: String, corners: List<Point>?): Result {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val warped = if (corners != null && corners.size >= 4) {
            warpBoard(src, corners)
        } else src.clone()

        val gray = Mat()
        Imgproc.cvtColor(warped, gray, Imgproc.COLOR_RGB2GRAY)

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)
        clahe.release()

        val blurred = Mat()
        Imgproc.bilateralFilter(enhanced, blurred, 9, 75.0, 75.0)

        val balls = detectBalls(blurred)
        blurred.release()
        enhanced.release()
        gray.release()
        warped.release()
        src.release()

        val (smoothness, successRate) = analyzeDistribution(balls)

        val volumePerBall = volumePerBall(boardType)
        return Result(
            success = balls.isNotEmpty(),
            count = balls.size,
            totalVolumeMl = balls.size * volumePerBall,
            balls = balls,
            smoothness = smoothness,
            successRate = successRate
        )
    }

    private fun warpBoard(src: Mat, corners: List<Point>): Mat {
        val tl = corners[0]
        val tr = corners[1]
        val bl = corners[2]
        val br = corners[3]

        val widthTop = distance(tl, tr)
        val widthBot = distance(bl, br)
        val maxW = maxOf(widthTop, widthBot)

        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)
        val maxH = maxOf(heightLeft, heightRight)

        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxW - 1, 0.0),
            Point(0.0, maxH - 1),
            Point(maxW - 1, maxH - 1)
        )

        val srcMat = MatOfPoint2f(tl, tr, bl, br)
        val m = Calib3d.getPerspectiveTransform(srcMat, dst)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, m, Size(maxW, maxH))
        m.release()
        srcMat.release()
        dst.release()
        return warped
    }

    private fun detectBalls(gray: Mat): List<Ball> {
        val circles = Mat()
        Imgproc.HoughCircles(
            gray, circles, Imgproc.CV_HOUGH_GRADIENT,
            dp = 1.0,
            minDist = 15.0,
            param1 = 60.0,
            param2 = 22.0,
            minRadius = 4,
            maxRadius = 40
        )

        val result = mutableListOf<Ball>()
        if (circles.empty()) return result

        for (i in 0 until circles.rows()) {
            val x = circles[i, 0][0]
            val y = circles[i, 1][0]
            val r = circles[i, 2][0]
            if (r > 0) {
                result.add(Ball(x, y, r, Math.PI * r * r))
            }
        }
        circles.release()
        return result.sortedBy { it.y }
    }

    private fun analyzeDistribution(balls: List<Ball>): Pair<Double, Double> {
        if (balls.size < 2) return Pair(0.0, 0.0)

        val ys = balls.map { it.y }
        val meanY = ys.average()
        val variance = ys.map { (it - meanY).pow(2) }.average()
        val std = sqrt(variance)

        val rangeY = ys.maxOrNull()!! - ys.minOrNull()!!
        val smoothness = if (rangeY > 0) 1.0 - (std / rangeY).coerceIn(0.0, 1.0) else 1.0

        val totalArea = balls.sumOf { it.area }
        val avgArea = totalArea / balls.size
        val areaCv = sqrt(balls.map { (it.area - avgArea).pow(2) }.average()) / avgArea
        val successRate = (1.0 - areaCv.coerceIn(0.0, 1.0))

        return Pair(smoothness, successRate)
    }

    private fun volumePerBall(boardType: String): Double {
        return when (boardType) {
            "Board4000" -> 3.0
            else -> 2.0
        }
    }

    private fun distance(a: Point, b: Point): Double {
        return sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))
    }
}
