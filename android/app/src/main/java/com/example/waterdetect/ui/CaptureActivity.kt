package com.example.waterdetect.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.waterdetect.ImageHolder
import com.example.waterdetect.R
import com.example.waterdetect.cv.CornerDetector
import com.example.waterdetect.cv.OpenCvEngine
import com.example.waterdetect.databinding.ActivityCaptureBinding
import kotlin.math.max

class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bmp = ImageHolder.bitmap
        val corners = ImageHolder.corners
        if (bmp == null || corners == null) {
            finish(); return
        }
        binding.cornerView.setData(bmp, corners)

        val snapAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("吸附·弱", "吸附·中", "吸附·强")
        )
        snapAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.snapLevel.adapter = snapAdapter
        binding.snapLevel.setSelection(1)
        binding.snapLevel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                binding.cornerView.setSnapLevel(when (pos) { 0 -> "weak"; 2 -> "strong"; else -> "mid" })
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }

        binding.snapSwitch.setOnCheckedChangeListener { _, on -> binding.cornerView.setSnapEnabled(on) }

        binding.magZoom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, prog: Int, fromUser: Boolean) {
                val z = max(1, prog)
                binding.cornerView.setMagZoom(z)
                binding.magZoomVal.text = "${z}×"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })

        binding.btnDetect.setOnClickListener {
            val mat = OpenCvEngine.bitmapToMat(bmp)
            val res = CornerDetector.detectRedCorners(mat, ImageHolder.boardType)
            mat.release()
            binding.cornerView.setData(bmp, res.cornerPoints)
        }

        binding.btnAnalyze.setOnClickListener {
            ImageHolder.corners = binding.cornerView.getCorners()
            startActivity(Intent(this, ResultActivity::class.java))
        }
    }
}
