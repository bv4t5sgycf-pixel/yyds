package com.example.waterdetect.cv

import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_core.Point
import org.bytedeco.opencv.opencv_core.Point2f
import org.bytedeco.opencv.opencv_core.Rect
import kotlin.math.*

/**
 * 水量分布检测核心（Kotlin 翻译自 PWA analyzer.js / 后端 detector.py）。
 * 全部在本地 OpenCV 原生库完成，无后端、可离线。
 * 使用 bytedeco 完整 OpenCV 构建：Core.split/merge 走 MatVector，函数/常量来自 global 包。
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
        opencv_imgproc.cvtColor(roi, lab, opencv_imgproc.COLOR_BGR2Lab)
        val ch = MatVector()
        opencv_core.split(lab, ch)
        val l = ch.get(0L); val a = ch.get(1L); val b = ch.get(2L)
        val clahe = opencv_imgproc.createCLAHE(2.5, Size(4, 4))
        val l2 = Mat()
        clahe.apply(l, l2)
        val mergedVec = MatVector()
        mergedVec.push_back(l2); mergedVec.push_back(a); mergedVec.push_back(b)
        val merged = Mat()
        opencv_core.merge(mergedVec, merged)
        val enhanced = Mat()
        opencv_imgproc.cvtColor(merged, enhanced, opencv_imgproc.COLOR_Lab2BGR)
        // bilateralFilter 不支持 in-place（src 与 dst 不能是同一个 Mat）
        val filtered = Mat()
        opencv_imgproc.bilateralFilter(enhanced, filtered, 5, 50.0, 50.0)
        lab.release()
        l.release(); a.release(); b.release()
        l2.release(); merged.release(); enhanced.release()
        clahe.deallocate(); mergedVec.deallocate()
        return filtered
    }

    // ── 绿色主导 + 高饱和 + 亮 掩膜（对应 greenBallMaskJS）──
    private fun greenBallMask(bgr: Mat, hsv: Mat): Mat {
        val bgrCh = MatVector(); opencv_core.split(bgr, bgrCh)
        val r = bgrCh.get(2L); val g = bgrCh.get(1L); val bCh = bgrCh.get(0L)
        val hsvCh = MatVector(); opencv_core.split(hsv, hsvCh)
        val s = hsvCh.get(1L); val v = hsvCh.get(2L)
        // r+10 / b+10（饱和加法），bytedeco add(Mat,Scalar) 返回 MatExpr，用 asMat() 落地
        val rp = opencv_core.add(r, Scalar(5.0)).asMat()
        val bp = opencv_core.add(bCh, Scalar(5.0)).asMat()
        val m1 = Mat(); opencv_core.compare(g, rp, m1, opencv_core.CMP_GT)
        val m2 = Mat(); opencv_core.compare(g, bp, m2, opencv_core.CMP_GT)
        // 单通道阈值（bytedeco compare 的第二参数必须是 Mat，用 1x1 CV_8UC1 承载标量）
        val sThresh = Mat(Size(1, 1), opencv_core.CV_8UC(1), Scalar(55.0))
        val vThresh = Mat(Size(1, 1), opencv_core.CV_8UC(1), Scalar(75.0))
        val ms = Mat(); opencv_core.compare(s, sThresh, ms, opencv_core.CMP_GT)
        val mv = Mat(); opencv_core.compare(v, vThresh, mv, opencv_core.CMP_GT)
        val t1 = Mat(); opencv_core.bitwise_and(m1, m2, t1)
        val t2 = Mat(); opencv_core.bitwise_and(t1, ms, t2)
        val out = Mat(); opencv_core.bitwise_and(t2, mv, out)
        listOf(rp, bp, m1, m2, ms, mv, t1, t2).forEach { it.release() }
        listOf(sThresh, vThresh).forEach { it.release() }
        for (i in 0 until bgrCh.size().toInt()) bgrCh.get(i.toLong()).release()
        bgrCh.deallocate()
        for (i in 0 until hsvCh.size().toInt()) hsvCh.get(i.toLong()).release()
        hsvCh.deallocate()
        return out
    }

    private fun brightSatMask(bgr: Mat, hsv: Mat): Mat {
        val hsvCh = MatVector(); opencv_core.split(hsv, hsvCh)
        val s = hsvCh.get(1L); val v = hsvCh.get(2L)
        val sLo = Mat(Size(1, 1), opencv_core.CV_8UC(1), Scalar(55.0))
        val vLo = Mat(Size(1, 1), opencv_core.CV_8UC(1), Scalar(75.0))
        val vHi = Mat(Size(1, 1), opencv_core.CV_8UC(1), Scalar(255.0))
        val ms1 = Mat(); opencv_core.compare(s, sLo, ms1, opencv_core.CMP_GT)
        val mv1 = Mat(); opencv_core.compare(v, vLo, mv1, opencv_core.CMP_GT)
        val mv2 = Mat(); opencv_core.compare(v, vHi, mv2, opencv_core.CMP_LT)
        val t1 = Mat(); opencv_core.bitwise_and(ms1, mv1, t1)
        val out = Mat(); opencv_core.bitwise_and(t1, mv2, out)
        listOf(ms1, mv1, mv2, t1).forEach { it.release() }
        listOf(sLo, vLo, vHi).forEach { it.release() }
        for (i in 0 until hsvCh.size().toInt()) hsvCh.get(i.toLong()).release()
        hsvCh.deallocate()
        return out
    }

    private fun candidatesFrom(mask: Mat): List<Cand> {
        val kernel = opencv_imgproc.getStructuringElement(opencv_imgproc.MORPH_ELLIPSE, Size(3, 3))
        val opened = Mat()
        opencv_imgproc.morphologyEx(mask, opened, opencv_imgproc.MORPH_OPEN, kernel)
        val contours = MatVector()
        val hierarchy = Mat()
        opencv_imgproc.findContours(opened, contours, hierarchy, opencv_imgproc.RETR_EXTERNAL, opencv_imgproc.CHAIN_APPROX_SIMPLE)
        val out = mutableListOf<Cand>()
        val h = mask.rows().toDouble()
        val n = contours.size().toInt()
        for (k in 0 until n) {
            val cnt = contours.get(k.toLong())
            val area = opencv_imgproc.contourArea(cnt)
            if (area < 2) continue
            val mom = opencv_imgproc.moments(cnt)
            if (mom.m00() == 0.0) continue
            val cy = mom.m01() / mom.m00()
            if (cy < max(6.0, h * 0.03) || cy > h * 0.99) continue
            val peri = opencv_imgproc.arcLength(cnt, true)
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
        opencv_imgproc.cvtColor(roiCol, hsv, opencv_imgproc.COLOR_BGR2HSV)
        var cands = candidatesFrom(greenBallMask(roiCol, hsv))
        if (cands.isEmpty()) cands = candidatesFrom(brightSatMask(roiCol, hsv))
        hsv.release()
        if (cands.isEmpty()) return null to 0.0
        cands = cands.sortedWith(compareByDescending<Cand> { it.area }.thenBy { it.cy })
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
            // bytedeco 无 submat(int,int,int,int)，用 Mat(m, Rect) 取列条带
            val roiCol = Mat(proc, Rect(x1, 0, x2 - x1, hHi))
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
        val outW = calib.first * WARP_PX_PER_MM
        val outH = (calib.second + WARP_HEADROOM_MM) * WARP_PX_PER_MM
        // bytedeco getPerspectiveTransform 接收 Point2f（指针数组，容量 4）
        val srcPts = Point2f(4L)
        srcPts.position(0L).x(src[0].x().toFloat()).y(src[0].y().toFloat())
        srcPts.position(1L).x(src[1].x().toFloat()).y(src[1].y().toFloat())
        srcPts.position(2L).x(src[2].x().toFloat()).y(src[2].y().toFloat())
        srcPts.position(3L).x(src[3].x().toFloat()).y(src[3].y().toFloat())
        val dstPts = Point2f(4L)
        dstPts.position(0L).x(0f).y(0f)
        dstPts.position(1L).x((outW - 1).toFloat()).y(0f)
        dstPts.position(2L).x(0f).y((outH - 1).toFloat())
        dstPts.position(3L).x((outW - 1).toFloat()).y((outH - 1).toFloat())
        val m = opencv_imgproc.getPerspectiveTransform(srcPts, dstPts)
        val warped = Mat()
        opencv_imgproc.warpPerspective(image, warped, m, Size(outW, outH))
        srcPts.deallocate(); dstPts.deallocate(); m.release()
        return warped
    }

    fun toDisplayImage(warpedHi: Mat, boardType: String = "Board1200"): Mat {
        val calib = BOARD_CALIBRATION[boardType] ?: BOARD_CALIBRATION["Board1200"]!!
        val dispW = calib.first
        val dispH = calib.second + WARP_HEADROOM_MM
        val dst = Mat()
        opencv_imgproc.resize(warpedHi, dst, Size(dispW, dispH), 0.0, 0.0, opencv_imgproc.INTER_AREA)
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
            val color = if (i % 10 == 0) Scalar(180.0, 180.0, 180.0, 0.0) else Scalar(220.0, 220.0, 220.0, 0.0)
            opencv_imgproc.line(result, Point(x, 0), Point(x, h - 1), color)
        }

        val linePts = detection.smoothedPoints.ifEmpty { detection.points }
        val valid = linePts.filter { it.second != null }
            .map { Point(round(it.first).toInt(), round(it.second!!).toInt()) }
        for (p in 0 until valid.size - 1) {
            opencv_imgproc.line(result, valid[p], valid[p + 1], Scalar(0.0, 0.0, 255.0, 0.0))
        }

        for (q in detection.points.indices) {
            val pt = detection.points[q]
            if (pt.second != null && detection.confidences[q] > 0.15) {
                val c = Point(round(pt.first).toInt(), round(pt.second!!).toInt())
                opencv_imgproc.circle(result, c, 4, Scalar(0.0, 255.0, 255.0, 0.0))
                opencv_imgproc.circle(result, c, 4, Scalar(0.0, 0.0, 255.0, 0.0))
            }
        }

        val font = opencv_imgproc.FONT_HERSHEY_SIMPLEX
        for (t in 0..tubeCount step 10) {
            val lx = round((t + 0.5) * colWidth).toInt()
            val label = if (t < tubeCount) (t + 1).toString() else tubeCount.toString()
            opencv_imgproc.putText(result, label, Point(lx - 8, 18), font, 0.4, Scalar(80.0, 80.0, 80.0, 0.0))
        }
        val info = "$boardType | tubes:$tubeCount | success:${(detection.successRate).toFixed(1)}%"
        opencv_imgproc.putText(result, info, Point(10, h - 15), font, 0.45, Scalar(0.0, 0.0, 255.0, 0.0))
        return result
    }

    private fun Double.toFixed(n: Int): String = "%.${n}f".format(this)

    /** 平滑度切换时复用已检测的 rawCys 重新 finalize，免去整图重检测。 */
    fun recompute(prev: DetectResult, smoothWindow: Int, boardType: String): DetectResult {
        val validCount = prev.rawCys.count { it != null }
        return finalize(prev.rawCys, prev.confidences, prev.points, validCount, prev.tubeCount, 0.0, boardType, smoothWindow)
    }

    /** 诊断掩膜：把 greenBallMask + brightSatMask 以绿色半透明叠在预处理图上，帮助定位水球漏检。 */
    fun debugMaskOverlay(warpedHi: Mat): Mat {
        val proc = preprocess(warpedHi)
        val hsv = Mat()
        opencv_imgproc.cvtColor(proc, hsv, opencv_imgproc.COLOR_BGR2HSV)
        val mask1 = greenBallMask(proc, hsv)
        val mask2 = brightSatMask(proc, hsv)
        val mask = Mat()
        opencv_core.bitwise_or(mask1, mask2, mask)
        val maskBgr = Mat()
        opencv_imgproc.cvtColor(mask, maskBgr, opencv_imgproc.COLOR_GRAY2BGR)
        // 把掩膜区域染成绿色（保留原图 60%）
        val greenMask = Mat(maskBgr.size(), maskBgr.type(), Scalar(0.0, 255.0, 0.0, 0.0))
        val tinted = Mat()
        opencv_core.bitwise_and(greenMask, maskBgr, tinted)
        val overlay = Mat()
        opencv_core.addWeighted(proc, 0.65, tinted, 0.55, 0.0, overlay)
        hsv.release(); mask1.release(); mask2.release(); mask.release(); maskBgr.release(); greenMask.release(); tinted.release(); proc.release()
        return overlay
    }
}
