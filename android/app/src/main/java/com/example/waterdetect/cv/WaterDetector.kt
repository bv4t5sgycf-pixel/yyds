package com.example.waterdetect.cv

import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.opencv.global.opencv_calib3d.Calib3d
import kotlin.math.*

/**
 * 水量分布检测核心（Kotlin 翻译自 PWA analyzer.js / 后端 detector.py）。
 * 全部在本地 OpenCV 原生库完成，无后端、可离线。
 * 使用 bytedeco 完整 OpenCV 构建：Core.split/merge 走 MatVector，常量来自 global 包。
 */
object WaterDetector {

    private const val WARP_PX_PER_MM = 4
    private const val WARP_HEADROOM_MM = 24
    private const val BALL_DIAMETER_RATIO = 0.06
    private const val DEFAULT_SMOOTH_WINDOW = 3

    // boardType -> (board_w_mm, full_height_mm, tube_count)
    private val BOARD_CALIBRATION = mapOf(
        "Board1008" to Triple(1008, 280, 84),
        "Board1200" to Triple(1200, 280, 100),
        "Board4000" to Triple(4000, 370, 200)
    )

    private data class Cand(val cy: Double, val area: Double, val circ: Double)

    data class DetectResult(
        val heights: List<Double?>,
        val confidences: List<Double>,
        val points: List<Pair<Double, Double?>>,
        val rawCys: List<Double?>,
        val smoothedPoints: List<Pair<Double, Double?>>,
        val successRate: Double,
        val fullHeightMm: Int,
        val tubeCount: Int
    )

    private fun round2(x: Double): Double = round(x * 100) / 100

    // ── 预处理：CLAHE + 双边滤波 ──
    private fun preprocess(roi: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(roi, lab, COLOR_BGR2LAB)
        val ch = MatVector()
        Core.split(lab, ch)
        val l = ch[0]; val a = ch[1]; val b = ch[2]
        val clahe = Imgproc.createCLAHE(2.5, Size(4.0, 4.0))
        val l2 = Mat()
        clahe.apply(l, l2)
        val mergedVec = MatVector()
        mergedVec.push_back(l2); mergedVec.push_back(a); mergedVec.push_back(b)
        val merged = Mat()
        Core.merge(mergedVec, merged)
        val enhanced = Mat()
        Imgproc.cvtColor(merged, enhanced, COLOR_LAB2BGR)
        Imgproc.bilateralFilter(enhanced, enhanced, 5, 50.0, 50.0)
        lab.release()
        l.release(); a.release(); b.release()
        l2.release(); merged.release()
        clahe.deallocate(); mergedVec.deallocate()
        return enhanced
    }

    // ── 绿色主导 + 高饱和 + 亮 掩膜（对应 greenBallMaskJS）──
    private fun greenBallMask(bgr: Mat, hsv: Mat): Mat {
        val bgrCh = MatVector(); Core.split(bgr, bgrCh)
        val r = bgrCh[2]; val g = bgrCh[1]; val bCh = bgrCh[0]
        val hsvCh = MatVector(); Core.split(hsv, hsvCh)
        val s = hsvCh[1]; val v = hsvCh[2]
        val rp = Mat(); Core.add(r, Scalar(10.0), rp)
        val bp = Mat(); Core.add(bCh, Scalar(10.0), bp)
        val m1 = Mat(); Core.compare(g, rp, m1, CMP_GT)
        val m2 = Mat(); Core.compare(g, bp, m2, CMP_GT)
        val ms = Mat(); Core.compare(s, Scalar(85.0), ms, CMP_GT)
        val mv = Mat(); Core.compare(v, Scalar(110.0), mv, CMP_GT)
        val t1 = Mat(); Core.bitwise_and(m1, m2, t1)
        val t2 = Mat(); Core.bitwise_and(t1, ms, t2)
        val out = Mat(); Core.bitwise_and(t2, mv, out)
        listOf(rp, bp, m1, m2, ms, mv, t1, t2).forEach { it.release() }
        for (i in 0 until bgrCh.size().toInt()) bgrCh[i].release()
        bgrCh.deallocate()
        for (i in 0 until hsvCh.size().toInt()) hsvCh[i].release()
        hsvCh.deallocate()
        return out
    }

    private fun brightSatMask(bgr: Mat, hsv: Mat): Mat {
        val hsvCh = MatVector(); Core.split(hsv, hsvCh)
        val s = hsvCh[1]; val v = hsvCh[2]
        val ms1 = Mat(); Core.compare(s, Scalar(90.0), ms1, CMP_GT)
        val mv1 = Mat(); Core.compare(v, Scalar(120.0), mv1, CMP_GT)
        val mv2 = Mat(); Core.compare(v, Scalar(250.0), mv2, CMP_LT)
        val t1 = Mat(); Core.bitwise_and(ms1, mv1, t1)
        val out = Mat(); Core.bitwise_and(t1, mv2, out)
        listOf(ms1, mv1, mv2, t1).forEach { it.release() }
        for (i in 0 until hsvCh.size().toInt()) hsvCh[i].release()
        hsvCh.deallocate()
        return out
    }

    private fun candidatesFrom(mask: Mat): List<Cand> {
        val kernel = Imgproc.getStructuringElement(MORPH_ELLIPSE, Size(3.0, 3.0))
        val opened = Mat()
        Imgproc.morphologyEx(mask, opened, MORPH_OPEN, kernel)
        val contours = MatVector()
        val hierarchy = Mat()
        Imgproc.findContours(opened, contours, hierarchy, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE)
        val out = mutableListOf<Cand>()
        val h = mask.rows().toDouble()
        val n = contours.size().toInt()
        for (k in 0 until n) {
            val cnt = contours[k]
            val area = Imgproc.contourArea(cnt)
            if (area < 4) continue
            val mom = Imgproc.moments(cnt)
            if (mom.m00() == 0.0) continue
            val cy = mom.m01() / mom.m00()
            if (cy < max(6.0, h * 0.03) || cy > h * 0.99) continue
            val peri = Imgproc.arcLength(cnt, true)
            val circ = if (peri > 0) (4 * Math.PI * area / (peri * peri + 1e-6)) else 0.0
            out.add(Cand(cy, area, circ))
        }
        kernel.release(); opened.release(); hierarchy.release(); contours.deallocate()
        return out
    }

    private fun detectColumnBall(roiCol: Mat): Pair<Double?, Double> {
        val h = roiCol.rows(); val w = roiCol.cols()
        if (h < 20 || w < 3) return null to 0.0
        val hsv = Mat()
        Imgproc.cvtColor(roiCol, hsv, COLOR_BGR2HSV)
        var cands = candidatesFrom(greenBallMask(roiCol, hsv))
        if (cands.isEmpty()) cands = candidatesFrom(brightSatMask(roiCol, hsv))
        hsv.release()
        if (cands.isEmpty()) return null to 0.0
        cands.sortWith(compareByDescending<Cand> { it.area }.thenBy { it.cy })
        val best = cands[0]
        val areaScore = min(1.0, best.area / 40.0)
        val conf = min(1.0, 0.4 + best.circ * 0.35 + areaScore * 0.25)
        return best.cy to round2(conf)
    }

    // ── 中值平滑 + None 线性插值（对应 smoothCys）──
    fun smoothCys(cys: List<Double?>, window: Int): List<Double?> {
        val n = cys.size
        if (n < window) return cys.toList()
        val filled = Array<Double?>(n) { null }
        val validIdx = mutableListOf<Int>()
        for (i in 0 until n) if (cys[i] != null) validIdx.add(i)
        if (validIdx.isEmpty()) return cys.toList()
        for (j in 0 until n) {
            if (cys[j] != null) { filled[j] = cys[j]; continue }
            var li = validIdx[0]; var ri = validIdx[validIdx.size - 1]
            for (a in validIdx.size - 1 downTo 0) { if (validIdx[a] <= j) { li = validIdx[a]; break } }
            for (b in 0 until validIdx.size) { if (validIdx[b] >= j) { ri = validIdx[b]; break } }
            filled[j] = if (li == ri) cys[li] else {
                val ly = cys[li]!!; val ry = cys[ri]!!
                val t = (j - li).toDouble() / (ri - li)
                ly + t * (ry - ly)
            }
        }
        val half = window / 2
        val smoothed = mutableListOf<Double?>()
        for (x in 0 until n) {
            val l = max(0, x - half); val r = min(n, x + half + 1)
            val slice = filled.slice(l until r).filterNotNull().sorted()
            smoothed.add(round2(slice[slice.size / 2]))
        }
        return smoothed
    }

    private fun finalize(
        rawCys: List<Double?>,
        confidences: List<Double>,
        points: List<Pair<Double, Double?>>,
        validCount: Int,
        tubeCount: Int,
        zeroOffset: Double,
        boardType: String,
        smoothWindow: Int
    ): DetectResult {
        val calib = BOARD_CALIBRATION[boardType] ?: BOARD_CALIBRATION["Board1200"]!!
        val fullHeightMm = calib.second
        val dispW = calib.first
        val dispH = calib.second + WARP_HEADROOM_MM
        val scale = WARP_PX_PER_MM.toDouble()

        val smoothedCys = smoothCys(rawCys, smoothWindow)
        val heights = mutableListOf<Double?>()
        val smoothedPoints = mutableListOf<Pair<Double, Double?>>()
        for (i in smoothedCys.indices) {
            val cyHi = smoothedCys[i]
            if (cyHi != null) {
                val cyDisp = cyHi / scale
                val heightPx = dispH - 1 - cyDisp
                val heightMm = max(0.0, round2(heightPx - zeroOffset))
                heights.add(heightMm)
                val cx = round2((i + 0.5) * dispW / tubeCount)
                smoothedPoints.add(cx to round2(cyDisp))
            } else {
                heights.add(null)
                val cx = round2((i + 0.5) * dispW / tubeCount)
                smoothedPoints.add(cx to null)
            }
        }
        val successRate = if (tubeCount > 0) round2(validCount.toDouble() / tubeCount * 100) else 0.0
        return DetectResult(
            heights = heights,
            confidences = confidences,
            points = points,
            rawCys = rawCys,
            smoothedPoints = smoothedPoints,
            successRate = successRate,
            fullHeightMm = fullHeightMm,
            tubeCount = tubeCount
        )
    }

    /** 主检测：warpedHi 已是透视校正后的高分辨率正视图（像素坐标）。 */
    fun detectWaterBalls(
        warpedHi: Mat,
        tubeCount: Int,
        zeroOffset: Double = 0.0,
        boardType: String = "Board1200",
        smoothWindow: Int = DEFAULT_SMOOTH_WINDOW
    ): DetectResult {
        var sw = if (smoothWindow < 1) DEFAULT_SMOOTH_WINDOW else smoothWindow
        if (sw % 2 == 0) sw += 1

        val scale = WARP_PX_PER_MM.toDouble()
        val hHi = warpedHi.rows(); val wHi = warpedHi.cols()
        val colWidth = wHi.toDouble() / tubeCount
        val roiWidth = max(4, floor(colWidth * 0.8).toInt())
        val calib = BOARD_CALIBRATION[boardType] ?: BOARD_CALIBRATION["Board1200"]!!
        val dispW = calib.first
        val dispH = calib.second + WARP_HEADROOM_MM

        val proc = preprocess(warpedHi)
        val rawCys = mutableListOf<Double?>()
        val confidences = mutableListOf<Double>()
        val points = mutableListOf<Pair<Double, Double?>>()
        var validCount = 0
        for (i in 0 until tubeCount) {
            val cxHi = floor((i + 0.5) * colWidth).toInt()
            val x1 = max(0, cxHi - roiWidth / 2)
            val x2 = min(wHi, x1 + roiWidth)
            val roiCol = proc.submat(0, hHi, x1, x2)
            val (cy, conf) = detectColumnBall(roiCol)
            roiCol.release()
            rawCys.add(cy)
            confidences.add(conf)
            if (cy != null) {
                val cyDisp = cy / scale
                val cxDisp = cxHi / scale
                points.add(round2(cxDisp) to round2(cyDisp))
                validCount++
            } else {
                val cx = round2((i + 0.5) * dispW / tubeCount)
                points.add(cx to null)
            }
        }
        proc.release()
        return finalize(rawCys, confidences, points, validCount, tubeCount, zeroOffset, boardType, sw)
    }

    // ── 透视校正 ──
    private fun orderPoints(pts: List<Point>): List<Point> {
        val s = pts.map { it.x() + it.y() }
        val diff = pts.map { it.x() - it.y() }
        val tl = pts[s.indexOf(s.minOrNull()!!)]
        val br = pts[s.indexOf(s.maxOrNull()!!)]
        val tr = pts[diff.indexOf(diff.minOrNull()!!)]
        val bl = pts[diff.indexOf(diff.maxOrNull()!!)]
        return listOf(tl, tr, bl, br)
    }

    fun warpPerspective(image: Mat, corners: List<Point>, boardType: String = "Board1200"): Mat {
        val src = orderPoints(corners)
        val calib = BOARD_CALIBRATION[boardType] ?: BOARD_CALIBRATION["Board1200"]!!
        val outW = (calib.first * WARP_PX_PER_MM)
        val outH = ((calib.second + WARP_HEADROOM_MM) * WARP_PX_PER_MM)
        val srcMat = MatOfPoint2f(*src.toTypedArray())
        val dstMat = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((outW - 1).toDouble(), 0.0),
            Point(0.0, (outH - 1).toDouble()),
            Point((outW - 1).toDouble(), (outH - 1).toDouble())
        )
        val m = Calib3d.getPerspectiveTransform(srcMat, dstMat)
        val warped = Mat()
        Imgproc.warpPerspective(
            image, warped, m,
            Size(outW.toDouble(), outH.toDouble()), INTER_LINEAR
        )
        srcMat.release(); dstMat.release(); m.release()
        return warped
    }

    fun toDisplayImage(warpedHi: Mat, boardType: String = "Board1200"): Mat {
        val calib = BOARD_CALIBRATION[boardType] ?: BOARD_CALIBRATION["Board1200"]!!
        val dispW = calib.first
        val dispH = calib.second + WARP_HEADROOM_MM
        val dst = Mat()
        Imgproc.resize(warpedHi, dst, Size(dispW.toDouble(), dispH.toDouble()), 0.0, 0.0, INTER_AREA)
        return dst
    }

    /** 结果图绘制：网格 + 水位折线 + 检测点 + 标签。 */
    fun drawResult(warpedImage: Mat, detection: DetectResult, boardType: String = "Board1200"): Mat {
        val result = warpedImage.clone()
        val h = result.rows(); val w = result.cols()
        val tubeCount = detection.tubeCount
        val colWidth = w.toDouble() / tubeCount

        for (i in 0..tubeCount) {
            val x = round(i * colWidth).toInt()
            val color = if (i % 10 == 0) Scalar(180.0, 180.0, 180.0) else Scalar(220.0, 220.0, 220.0)
            Imgproc.line(result, Point(x.toDouble(), 0.0), Point(x.toDouble(), (h - 1).toDouble()), color, 1)
        }

        val linePts = detection.smoothedPoints.ifEmpty { detection.points }
        val valid = linePts.filter { it.second != null }
            .map { Point(round(it.first).toDouble(), round(it.second!!).toDouble()) }
        for (p in 0 until valid.size - 1) {
            Imgproc.line(result, valid[p], valid[p + 1], Scalar(0.0, 0.0, 255.0), 2)
        }

        for (q in detection.points.indices) {
            val pt = detection.points[q]
            if (pt.second != null && detection.confidences[q] > 0.15) {
                val c = Point(round(pt.first).toDouble(), round(pt.second!!).toDouble())
                Imgproc.circle(result, c, 4, Scalar(0.0, 255.0, 255.0), -1)
                Imgproc.circle(result, c, 4, Scalar(0.0, 0.0, 255.0), 1)
            }
        }

        val font = FONT_HERSHEY_SIMPLEX
        for (t in 0..tubeCount step 10) {
            val lx = round((t + 0.5) * colWidth).toInt()
            val label = if (t < tubeCount) (t + 1).toString() else tubeCount.toString()
            Imgproc.putText(result, label, Point((lx - 8).toDouble(), 18.0), font, 0.4, Scalar(80.0, 80.0, 80.0), 1)
        }
        val info = "$boardType | tubes:$tubeCount | success:${(detection.successRate).toFixed(1)}%"
        Imgproc.putText(result, info, Point(10.0, (h - 15).toDouble()), font, 0.45, Scalar(0.0, 0.0, 255.0), 1)
        return result
    }

    private fun Double.toFixed(n: Int): String = "%.${n}f".format(this)

    /** 平滑度切换时复用已检测的 rawCys 重新 finalize，免去整图重检测。 */
    fun recompute(prev: DetectResult, smoothWindow: Int, boardType: String): DetectResult {
        val validCount = prev.rawCys.count { it != null }
        return finalize(prev.rawCys, prev.confidences, prev.points, validCount, prev.tubeCount, 0.0, boardType, smoothWindow)
    }
}
