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
import org.bytedeco.opencv.global.opencv_core.Mat
import org.bytedeco.opencv.global.opencv_core.Point

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private var warpMat: Mat? = null
    private var boardType = "Board1200"
    private var tubeCount = 100
    private var showDiag = false
    private var lastDrawMat: Mat? = null
    private var cacheResult: WaterDetector.DetectResult? = null

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
        val pixelCorners = corners.map { Point(it.rx * pw, it.ry * ph) }

        val mat = OpenCvEngine.bitmapToMat(bmp)
        val warp = WaterDetector.warpPerspective(mat, pixelCorners, boardType)
        mat.release()
        warpMat = warp

        runDetection(1)
        setupSmoothButtons()

        binding.btnDiag.setOnClickListener {
            showDiag = !showDiag
            redraw()
        }
    }

    private fun runDetection(smooth: Int) {
        val res = WaterDetector.detectWaterBalls(warpMat!!, tubeCount, 0.0, boardType, smooth)
        cacheResult = res
        updateText(res)
        redraw()
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
        val res = cacheResult ?: return
        val draw = if (showDiag) WaterDetector.drawResult(warpMat!!, res, boardType)
        else WaterDetector.toDisplayImage(warpMat!!, boardType)
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
                val recomputed = WaterDetector.recompute(prev, lvl, boardType)
                cacheResult = recomputed
                updateText(recomputed)
                highlight(btn)
                redraw()
            }
        }
        highlight(binding.smooth1)
    }

    override fun onDestroy() {
        super.onDestroy()
        warpMat?.release()
        lastDrawMat?.release()
    }
}
