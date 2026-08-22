package com.example.waterdetect.ui

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.waterdetect.ImageHolder
import com.example.waterdetect.R
import com.example.waterdetect.model.BoardPresets
import com.example.waterdetect.cv.OpenCvEngine
import com.example.waterdetect.cv.WaterDetector
import com.example.waterdetect.databinding.ActivityResultBinding
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private var warpMat: Mat? = null
    private var boardType = "Board1200"
    private var tubeCount = 100
    private var showDiag = 0   // 0=原透视图, 1=结果网格图, 2=掩膜诊断图
    private var lastDrawMat: Mat? = null
    private var cacheResult: WaterDetector.DetectResult? = null
    private var leftMargin = 9.0
    private var rightMargin = 9.0
    private var currentSmooth = 1
    private val diagLabels = listOf("查看诊断图", "查看原图", "查看掩膜")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bmp = ImageHolder.bitmap
        val corners = ImageHolder.corners
        if (bmp == null || corners == null) { finish(); return }
        boardType = ImageHolder.boardType
        tubeCount = BoardPresets.get(boardType).tubeCount

        val pw = bmp.width.toDouble(); val ph = bmp.height.toDouble()
        val pixelCorners = corners.map { Point((it.rx * pw).toInt(), (it.ry * ph).toInt()) }

        try {
            val mat = OpenCvEngine.bitmapToMat(bmp)
            val warp = WaterDetector.warpPerspective(mat, pixelCorners, boardType)
            mat.release()
            warpMat = warp

            runDetection(1)
            setupSmoothButtons()
            setupMarginInputs()

            binding.btnDiag.setOnClickListener {
                showDiag = (showDiag + 1) % 3
                binding.btnDiag.text = diagLabels[showDiag]
                redraw()
            }
        } catch (e: Exception) {
            android.util.Log.e("ResultActivity", "分析失败", e)
            android.widget.Toast.makeText(this, "分析失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun runDetection(smooth: Int) {
        currentSmooth = smooth
        try {
            val res = WaterDetector.detectWaterBalls(
                warpMat!!, tubeCount, 0.0, boardType, smooth,
                leftMarginMm = leftMargin, rightMarginMm = rightMargin
            )
            cacheResult = res
            updateText(res)
            redraw()
        } catch (e: Exception) {
            android.util.Log.e("ResultActivity", "水球检测失败", e)
            val detail = e.message + "\n" + e.stackTraceToString()
            binding.successText.text = "水球检测失败（请截图给我）：\n$detail"
            android.widget.Toast.makeText(this, "水球检测失败，详细错误已显示在页面", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun updateText(res: WaterDetector.DetectResult) {
        binding.successText.text =
            "识别成功率：${"%.1f".format(res.successRate)}% · 有效管：${res.heights.count { it != null }}/$tubeCount"
        val sb = StringBuilder()
        res.heights.forEachIndexed { i, h ->
            sb.append("${i + 1}:${if (h == null) "—" else h.toInt()}  ")
            if ((i + 1) % 12 == 0) sb.append("\n")
        }
        binding.waterList.text = sb.toString()
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
        binding.resultImage.setImageBitmap(OpenCvEngine.matToBitmap(draw))
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
                val recomputed = WaterDetector.recompute(prev, lvl, boardType)
                cacheResult = recomputed
                updateText(recomputed)
                highlight(btn)
                redraw()
            }
        }
        highlight(binding.smooth1)
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
