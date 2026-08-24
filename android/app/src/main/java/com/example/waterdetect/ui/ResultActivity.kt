package com.example.waterdetect.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.waterdetect.ImageHolder
import com.example.waterdetect.R
import com.example.waterdetect.model.BoardPresets
import com.example.waterdetect.cv.OpenCvEngine
import com.example.waterdetect.cv.WaterDetector
import com.example.waterdetect.data.AppSettings
import com.example.waterdetect.data.HistoryStore
import com.example.waterdetect.databinding.ActivityResultBinding
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point

/** 结果页（对齐小程序 pages/result）：统计卡 + 结果图 + 水量分布曲线 + 平滑/间距 + 数据表。 */
class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private var warpMat: Mat? = null
    private var boardType = "Board1200"
    private var tubeCount = 100
    private var showDiag = 0   // 0=原透视图, 1=结果网格图, 2=掩膜诊断图
    private var lastDrawMat: Mat? = null
    private var lastBitmap: Bitmap? = null
    private var cacheResult: WaterDetector.DetectResult? = null
    private var leftMargin = 9.0
    private var rightMargin = 9.0
    private var currentSmooth = 1
    private var zeroOffset = 0.0
    private var historySaved = false
    private var showTable = false
    private val diagLabels = listOf("查看诊断图", "查看原图", "查看掩膜")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val bmp = ImageHolder.bitmap
        val corners = ImageHolder.corners
        if (bmp == null || corners == null) { finish(); return }
        boardType = ImageHolder.boardType
        tubeCount = BoardPresets.get(boardType).tubeCount
        zeroOffset = AppSettings.getZeroOffset(this)

        val pw = bmp.width.toDouble(); val ph = bmp.height.toDouble()
        val pixelCorners = corners.map { Point((it.rx * pw).toInt(), (it.ry * ph).toInt()) }

        try {
            val mat = OpenCvEngine.bitmapToMat(bmp)
            val warp = WaterDetector.warpPerspective(mat, pixelCorners, boardType)
            mat.release()
            warpMat = warp

            currentSmooth = AppSettings.getSmoothWindow(this)
            runDetection(currentSmooth, saveHistory = true)
            setupSmoothButtons()
            setupMarginInputs()

            binding.btnDiag.setOnClickListener {
                showDiag = (showDiag + 1) % 3
                binding.btnDiag.text = diagLabels[showDiag]
                redraw()
            }

            binding.btnToggleTable.setOnClickListener {
                showTable = !showTable
                binding.cardTable.visibility = if (showTable) View.VISIBLE else View.GONE
                binding.btnToggleTable.text = if (showTable) "隐藏数据表" else "显示数据表"
            }

            binding.btnCopyData.setOnClickListener { copyData() }
        } catch (e: Exception) {
            android.util.Log.e("ResultActivity", "分析失败", e)
            Toast.makeText(this, "分析失败：${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun runDetection(smooth: Int, saveHistory: Boolean = false) {
        currentSmooth = smooth
        try {
            val res = WaterDetector.detectWaterBalls(
                warpMat!!, tubeCount, zeroOffset, boardType, smooth,
                leftMarginMm = leftMargin, rightMarginMm = rightMargin
            )
            cacheResult = res
            updateText(res)
            redraw()
            if (saveHistory) saveHistoryRecord(res)
        } catch (e: Exception) {
            android.util.Log.e("ResultActivity", "水球检测失败", e)
            val detail = e.message + "\n" + e.stackTraceToString()
            binding.successText.text = "水球检测失败（请截图给我）：\n$detail"
            Toast.makeText(this, "水球检测失败，详细错误已显示在页面", Toast.LENGTH_LONG).show()
        }
    }

    /** 统计口径与小程序 processResult 一致：漏检管按 0 参与统计。 */
    private fun computeStats(heights: List<Double?>): DoubleArray {
        var max = 0.0; var min = 99999.0; var sum = 0.0
        heights.forEach { h ->
            val v = h ?: 0.0
            if (v > max) max = v
            if (v < min) min = v
            sum += v
        }
        val n = heights.size
        val avg = if (n > 0) sum / n else 0.0
        var variance = 0.0
        if (n > 0) {
            heights.forEach { h -> val d = (h ?: 0.0) - avg; variance += d * d }
            variance /= n
        }
        val cv = if (avg > 0) kotlin.math.sqrt(variance) / avg * 100 else 0.0
        return doubleArrayOf(max, min, avg, cv)
    }

    private fun updateText(res: WaterDetector.DetectResult) {
        binding.successText.text =
            "识别成功率：${"%.1f".format(res.successRate)}% · 有效管：${res.heights.count { it != null }}/$tubeCount"

        val stats = computeStats(res.heights)
        binding.statMax.text = "%.1f".format(stats[0])
        binding.statMin.text = "%.1f".format(stats[1])
        binding.statAvg.text = "%.1f".format(stats[2])
        binding.statCv.text = "%.1f".format(stats[3]) + "%"
        binding.statCv.setTextColor(
            ContextCompat.getColor(this, if (stats[3] > 15) R.color.danger else R.color.success)
        )

        // 曲线：图框按板型物理比例
        val cfg = BoardPresets.get(boardType)
        binding.chartView.setData(res.heights, cfg.tubeCount, cfg.fullHeightMm, cfg.boardWmm)

        // 数据表：每行 5 组「管号:高度」
        val sb = StringBuilder()
        res.heights.forEachIndexed { i, h ->
            sb.append("${i + 1}:${if (h == null) "-" else "%.1f".format(h)}  ")
            if ((i + 1) % 5 == 0) sb.append("\n")
        }
        binding.waterList.text = sb.toString()
    }

    private fun copyData() {
        val res = cacheResult ?: return
        val data = res.heights.joinToString("\n") { "%.1f".format(it ?: 0.0) }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("waterHeights", data))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    /** 首次检测成功后自动存一条历史记录（含结果图）。 */
    private fun saveHistoryRecord(res: WaterDetector.DetectResult) {
        if (historySaved) return
        historySaved = true
        try {
            val stats = computeStats(res.heights)
            val name = BoardPresets.displayNames.firstOrNull { it.first == boardType }?.second ?: boardType
            HistoryStore.save(
                this, boardType, name, tubeCount,
                stats[0], stats[1], stats[2], stats[3],
                res.successRate, currentSmooth, res.heights, lastBitmap
            )
        } catch (e: Exception) {
            android.util.Log.e("ResultActivity", "历史记录保存失败", e)
        }
    }

    private fun redraw() {
        val draw = when (showDiag) {
            1 -> {
                val res = cacheResult ?: return
                // 必须在显示分辨率图（1px=1mm）上绘制，坐标才与 points(mm) 对齐
                val disp = WaterDetector.toDisplayImage(warpMat!!, boardType)
                val r = WaterDetector.drawResult(disp, res, boardType, leftMargin, rightMargin)
                disp.release()
                r
            }
            2 -> WaterDetector.debugMaskOverlay(warpMat!!)
            else -> WaterDetector.toDisplayImage(warpMat!!, boardType)
        }
        lastDrawMat?.release()
        lastDrawMat = draw
        val bmp = OpenCvEngine.matToBitmap(draw)
        lastBitmap = bmp
        binding.resultImage.setImageBitmap(bmp)
    }

    private fun setupSmoothButtons() {
        val map = listOf(
            binding.smooth1 to 1, binding.smooth3 to 3, binding.smooth5 to 5,
            binding.smooth7 to 7, binding.smooth9 to 9
        )
        val blue = ContextCompat.getColor(this, R.color.blue_primary)
        val gray = ContextCompat.getColor(this, R.color.text_sub)
        fun highlight(sel: com.google.android.material.button.MaterialButton) {
            map.forEach { (btn, _) ->
                btn.strokeColor = android.content.res.ColorStateList.valueOf(if (btn === sel) blue else gray)
            }
        }
        map.forEach { (btn, lvl) ->
            btn.setOnClickListener {
                val prev = cacheResult ?: return@setOnClickListener
                currentSmooth = lvl
                val recomputed = WaterDetector.recompute(prev, lvl, boardType, zeroOffset)
                cacheResult = recomputed
                updateText(recomputed)
                highlight(btn)
                redraw()
            }
        }
        highlight(map.firstOrNull { it.second == currentSmooth }?.first ?: binding.smooth1)
    }

    private fun setupMarginInputs() {
        fun parse(text: String?): Double {
            val v = text?.toDoubleOrNull()
            return if (v != null && v >= 0) v else 0.0
        }
        val rerun = {
            leftMargin = parse(binding.leftMarginInput.text.toString())
            rightMargin = parse(binding.rightMarginInput.text.toString())
            runDetection(currentSmooth)
        }
        binding.leftMarginInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rerun() }
        })
        binding.rightMarginInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rerun() }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        warpMat?.release()
        lastDrawMat?.release()
    }
}
