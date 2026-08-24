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
        val tubeCount: Int,
        val columnMethod: String = "unknown",
        val columnSnapRate: Double = 0.0
    )

    private fun round1(x: Double): Double = round(x * 10) / 10
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

    // ── 管列直接检测（自动修复视差）──
    // 校正后正视图的逐列亮度轮廓里，管体亮、分隔墙暗，呈单频率周期信号。
    // 以等分网格为强先验，逐管在其理想位 ±0.6*pitch 窗口内取「离网格理想位最近」的峰；
    // 窗口内无峰则保持网格位（不漂移、不塌陷）。轮廓异常整体回退等分（与旧逻辑一致）。
    private fun _columnBrightnessProfile(warpedImage: Mat): DoubleArray {
        val gray = Mat()
        opencv_imgproc.cvtColor(warpedImage, gray, opencv_imgproc.COLOR_BGR2GRAY)
        val h = gray.rows(); val w = gray.cols()
        val y0 = max(1, (h * 0.2).toInt())
        val y1 = max(y0 + 1, (h * 0.8).toInt())
        val band = Mat(gray, Rect(0, y0, w, y1 - y0))
        val bh = band.rows()
        val prof = DoubleArray(w)
        // 整行一次性读入 ByteArray（单次 JNI 调用，远快于逐字节 get），
        // 累加用显式赋值（复合赋值 += 在该 Kotlin 编译器上报 No set method 错误）。
        val row = ByteArray(w)
        for (y in 0 until bh) {
            band.ptr(y).get(row)
            for (x in 0 until w) prof[x] = prof[x] + (row[x].toInt() and 0xFF)
        }
        if (bh > 0) for (x in 0 until w) prof[x] = prof[x] / bh
        band.release(); gray.release()
        return prof
    }

    // 1D 高斯平滑（手动实现，避免依赖 bytedeco 浮点 Mat 读写；边界复制）
    private fun gaussian1d(signal: DoubleArray, sigma: Double): DoubleArray {
        val n = signal.size
        val radius = max(1, ceil(3.0 * sigma).toInt())
        val kernel = DoubleArray(2 * radius + 1)
        var sum = 0.0
        for (g in -radius..radius) {
            val v = exp(-(g * g) / (2.0 * sigma * sigma))
            kernel[g + radius] = v; sum += v
        }
        for (k in kernel.indices) kernel[k] /= sum
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            for (g in -radius..radius) {
                val j = (i + g).coerceIn(0, n - 1)
                acc += signal[j] * kernel[g + radius]
            }
            out[i] = acc
        }
        return out
    }

    // 找局部极大点，按亮度降序贪心 NMS（合并双倍频率，保留更亮者）。
    // 边界亦视为合法局部极大（当轮廓在该侧转向），避免边缘管中心漏检。
    private fun _findPeaks(prof: DoubleArray, minDistance: Double): List<Int> {
        val n = prof.size
        val cand = mutableListOf<Int>()
        for (j in 0 until n) {
            val leftOk = (j == 0) || (prof[j] > prof[j - 1])
            val rightOk = (j == n - 1) || (prof[j] >= prof[j + 1])
            if (leftOk && rightOk) cand.add(j)
        }
        cand.sortByDescending { prof[it] }
        val accepted = mutableListOf<Int>()
        for (j in cand) {
            if (accepted.all { abs(j - it) >= minDistance }) accepted.add(j)
        }
        accepted.sort()
        return accepted
    }

    // 等分网格列中心（回退用，与旧生产逻辑一致）
    private fun _gridCenters(tubeCount: Int, leftPx: Double, colWidth: Double): List<Double> {
        return (0 until tubeCount).map { leftPx + (it + 0.5) * colWidth }
    }

    private fun _detectPipeColumns(
        warpedImage: Mat, tubeCount: Int, leftPx: Double, wHi: Int, colWidth: Double
    ): Triple<List<Double>, String, Double> {
        val pitch = if (colWidth > 0) colWidth else wHi / max(1, tubeCount).toDouble()
        val profile = _columnBrightnessProfile(warpedImage)
        val sigma = max(1.5, pitch * 0.25)
        val prof = gaussian1d(profile, sigma)
        val peaks = _findPeaks(prof, max(2.0, pitch * 0.4))
        if (peaks.isEmpty()) {
            return Triple(_gridCenters(tubeCount, leftPx, colWidth), "grid", 0.0)
        }
        val win = pitch * 0.6
        val centers = MutableList(tubeCount) { 0.0 }
        val used = BooleanArray(peaks.size)
        var moved = 0
        for (i in 0 until tubeCount) {
            val ideal = leftPx + (i + 0.5) * colWidth
            var bestJ = -1
            var bestKey = Pair(1e18, -1e18)
            for (k in peaks.indices) {
                if (used[k]) continue
                val pk = peaks[k].toDouble()
                if (ideal - win <= pk && pk <= ideal + win) {
                    val key = Pair(abs(pk - ideal), -prof[peaks[k]])
                    if (key.first < bestKey.first || (key.first == bestKey.first && key.second < bestKey.second)) {
                        bestKey = key; bestJ = k
                    }
                }
            }
            if (bestJ >= 0) {
                centers[i] = peaks[bestJ].toDouble()
                used[bestJ] = true
                moved++
            } else {
                centers[i] = ideal
            }
        }
        val snapRate = moved.toDouble() / tubeCount
        // 轮廓异常（几乎抓不到峰）→ 回退等分
        if (snapRate < 0.3 || peaks.size < (tubeCount * 0.3).toInt()) {
            return Triple(_gridCenters(tubeCount, leftPx, colWidth), "grid", 0.0)
        }
        return Triple(centers, "detected", snapRate)
    }

    private fun finalize(
        rawCys: List<Double?>,
        confidences: List<Double>,
        points: List<Pair<Double, Double?>>,
        validCount: Int,
        tubeCount: Int,
        zeroOffset: Double,
        boardType: String,
        smoothWindow: Int,
        centersDisp: List<Double>
    ): DetectResult {
        val calib = BOARD_CALIBRATION[boardType] ?: BOARD_CALIBRATION["Board1200"]!!
        val fullHeightMm = calib.second
        val dispH = calib.second + WARP_HEADROOM_MM
        val scale = WARP_PX_PER_MM.toDouble()

        val smoothedCys = smoothCys(rawCys, smoothWindow)
        val heights = mutableListOf<Double?>()
        val smoothedPoints = mutableListOf<Pair<Double, Double?>>()
        for (i in smoothedCys.indices) {
            // centersDisp[i] 为各管列中心的 1:1 显示坐标 x(mm)，已含管列检测偏移或网格偏移
            val cxDisp = centersDisp[i]
            val cyHi = smoothedCys[i]
            if (cyHi != null) {
                val cyDisp = cyHi / scale
                val heightPx = dispH - 1 - cyDisp
                val heightMm = max(0.0, round1(heightPx - zeroOffset))
                heights.add(heightMm)
                smoothedPoints.add(cxDisp to round1(cyDisp))
            } else {
                heights.add(null)
                smoothedPoints.add(cxDisp to null)
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
        smoothWindow: Int = DEFAULT_SMOOTH_WINDOW,
        leftMarginMm: Double = 0.0,
        rightMarginMm: Double = 0.0
    ): DetectResult {
        var sw = if (smoothWindow < 1) DEFAULT_SMOOTH_WINDOW else smoothWindow
        if (sw % 2 == 0) sw += 1

        val scale = WARP_PX_PER_MM.toDouble()
        val hHi = warpedHi.rows(); val wHi = warpedHi.cols()
        // 左右间距(mm) → 高分辨率像素：补偿四角标记与首/末管的实际偏移（视差等）
        val leftPx = max(0.0, leftMarginMm) * scale
        val rightPx = max(0.0, rightMarginMm) * scale
        val usableW = max(wHi * 0.2, wHi - leftPx - rightPx)   // 至少保留 20% 宽度，防止误填过大
        val colWidth = usableW / tubeCount      // 高分辨率下的列宽
        val calib = BOARD_CALIBRATION[boardType] ?: BOARD_CALIBRATION["Board1200"]!!
        val dispH = calib.second + WARP_HEADROOM_MM

        val proc = preprocess(warpedHi)
        val rawCys = mutableListOf<Double?>()
        val confidences = mutableListOf<Double>()
        val points = mutableListOf<Pair<Double, Double?>>()
        var validCount = 0

        // —— 管列检测：优先用亮度轮廓直接检测，回退等分网格 ——
        val (centersHiTmp, columnMethod, snapRate) = _detectPipeColumns(warpedHi, tubeCount, leftPx, wHi, colWidth)
        val centersHi = centersHiTmp.toMutableList()
        var centersDisp = centersHi.map { it / scale }

        for (i in 0 until tubeCount) {
            val cxHi = round(centersHi[i]).toInt()
            // 自适应 ROI 半宽：不侵入相邻管，防止剪切/串扰
            val gapLeft = if (i > 0) (cxHi - centersHi[i - 1]) else colWidth
            val gapRight = if (i < tubeCount - 1) (centersHi[i + 1] - cxHi) else colWidth
            var roiHalf = min(0.45 * min(gapLeft, gapRight), 0.5 * colWidth).toInt()
            roiHalf = max(4, min(roiHalf, (colWidth * 0.49).toInt()))
            val x1 = max(0, cxHi - roiHalf)
            val x2 = min(wHi, cxHi + roiHalf)
            val x2f = if (x2 <= x1) min(wHi, x1 + 1) else x2
            // bytedeco 无 submat(int,int,int,int)，用 Mat(m, Rect) 取列条带
            val roiCol = Mat(proc, Rect(x1, 0, x2f - x1, hHi))
            val (cy, conf) = detectColumnBall(roiCol)
            roiCol.release()
            rawCys.add(cy)
            confidences.add(conf)
            val cxDisp = centersDisp[i]
            if (cy != null) {
                val cyDisp = cy / scale
                points.add(round1(cxDisp) to round1(cyDisp))
                validCount++
            } else {
                points.add(round1(cxDisp) to null)
            }
        }

        // —— 安全网：检测中心导致的离群读数，回退到网格中心重测 ——
        // 仅当某管读数相对邻居明显离群、且其检测中心相对网格偏移较大时才回退，
        // 既保留视差修正，又避免边缘伪峰把边缘管读成离群值（如末管被读满）。
        val gridCentersHi = _gridCenters(tubeCount, leftPx, colWidth)
        val outlierPx = 40.0 * scale          // ≈40mm：与「跳变>40mm」指标对齐，仅校正明显离群
        for (i in 0 until tubeCount) {
            if (rawCys[i] == null) continue
            val nb = mutableListOf<Double>()
            if (i - 1 >= 0 && rawCys[i - 1] != null) nb.add(rawCys[i - 1]!!)
            if (i + 1 < tubeCount && rawCys[i + 1] != null) nb.add(rawCys[i + 1]!!)
            if (nb.isEmpty()) continue
            val sorted = nb.sorted()
            val med = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                      else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            if (abs(rawCys[i]!! - med) > outlierPx &&
                abs(centersHi[i] - gridCentersHi[i]) > 0.3 * colWidth) {
                val cxG = round(gridCentersHi[i]).toInt()
                val gHalf = max(4, (colWidth * 0.4).toInt())
                val gx1 = max(0, cxG - gHalf)
                val gx2 = min(wHi, cxG + gHalf)
                if (gx2 > gx1) {
                    val roiG = Mat(proc, Rect(gx1, 0, gx2 - gx1, hHi))
                    val (cyG, confG) = detectColumnBall(roiG)
                    roiG.release()
                    if (cyG != null && abs(cyG - med) < abs(rawCys[i]!! - med)) {
                        rawCys[i] = cyG
                        confidences[i] = round2(confG)
                        centersHi[i] = gridCentersHi[i]
                        points[i] = round1(gridCentersHi[i] / scale) to round1(cyG / scale)
                    }
                }
            }
        }
        // 检测中心可能被安全网回退，刷新显示坐标
        centersDisp = centersHi.map { it / scale }

        proc.release()
        val base = finalize(rawCys, confidences, points, validCount, tubeCount, zeroOffset, boardType, sw, centersDisp)
        return base.copy(columnMethod = columnMethod, columnSnapRate = snapRate)
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

    /** 结果图绘制：网格 + 水位折线 + 检测点 + 标签。
     *  与 Python renderer.draw_result 一致：warpedImage 应为显示分辨率图（1px=1mm），
     *  网格/标签随左右间距偏移。 */
    fun drawResult(
        warpedImage: Mat,
        detection: DetectResult,
        boardType: String = "Board1200",
        leftMarginMm: Double = 0.0,
        rightMarginMm: Double = 0.0
    ): Mat {
        val result = warpedImage.clone()
        val h = result.rows(); val w = result.cols()
        val tubeCount = detection.tubeCount
        // 网格与标签随左右间距偏移（与 Python renderer.draw_result 一致）
        val leftM = max(0.0, leftMarginMm)
        val rightM = max(0.0, rightMarginMm)
        val usable = max(w * 0.2, w - leftM - rightM)
        val colWidth = usable / tubeCount

        for (i in 0..tubeCount) {
            val x = round(leftM + i * colWidth).toInt()
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
            val lx = round(leftM + (t + 0.5) * colWidth).toInt()
            val label = if (t < tubeCount) (t + 1).toString() else tubeCount.toString()
            opencv_imgproc.putText(result, label, Point(lx - 8, 18), font, 0.4, Scalar(80.0, 80.0, 80.0, 0.0))
        }
        val info = "$boardType | tubes:$tubeCount | success:${(detection.successRate).toFixed(1)}% | ${detection.columnMethod}"
        opencv_imgproc.putText(result, info, Point(10, h - 15), font, 0.45, Scalar(0.0, 0.0, 255.0, 0.0))
        return result
    }

    private fun Double.toFixed(n: Int): String = "%.${n}f".format(this)

    /** 平滑度切换时复用已检测的 rawCys 重新 finalize，免去整图重检测。 */
    fun recompute(prev: DetectResult, smoothWindow: Int, boardType: String, zeroOffset: Double = 0.0): DetectResult {
        val validCount = prev.rawCys.count { it != null }
        // 列中心显示坐标由已存 points 的 x 还原（与 detect_water_balls 输出一致）
        val centersDisp = prev.points.map { it.first }
        val base = finalize(prev.rawCys, prev.confidences, prev.points, validCount, prev.tubeCount, zeroOffset, boardType, smoothWindow, centersDisp)
        // 平滑不改变管列来源，沿用初次检测的列方法/吸附率
        return base.copy(columnMethod = prev.columnMethod, columnSnapRate = prev.columnSnapRate)
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
