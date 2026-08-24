package com.example.waterdetect.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.waterdetect.ImageHolder
import com.example.waterdetect.R
import com.example.waterdetect.cv.CornerDetector
import com.example.waterdetect.cv.OpenCvEngine
import com.example.waterdetect.data.AppSettings
import com.example.waterdetect.databinding.ActivityCaptureBinding
import com.example.waterdetect.model.BoardPresets
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 采集页（对齐小程序 pages/capture）：
 * Step0 拍照（预览/角标类型/拍照或相册）→ Step1 四角识别（拖动/吸附/放大镜）→ 逐管找球（跳结果页）。
 */
class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private var boardType: String = "Board1008"
    private var cameraUri: Uri? = null
    private var currentStep = 0

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadBitmap(it) }
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { loadBitmap(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OpenCvEngine.ensureInitialized()
        binding.btnBack.setOnClickListener { finish() }

        // 角标类型：红点为当前可用方案，ArUco 暂未支持
        binding.modeRedDot.setOnClickListener { setCornerMode("red_dot") }
        binding.modeAruco.setOnClickListener {
            Toast.makeText(this, "ArUco 角标暂未支持，当前使用红点标记", Toast.LENGTH_SHORT).show()
        }

        binding.btnTakePhoto.setOnClickListener { ensureCameraPermission { openCamera() } }
        binding.btnPick.setOnClickListener { pickLauncher.launch("image/*") }
        binding.btnReselect.setOnClickListener {
            ImageHolder.bitmap = null
            ImageHolder.corners = null
            binding.previewImage.setImageDrawable(null)
            showImageLoaded(false)
        }
        binding.btnStartDetect.setOnClickListener { startCornerDetect() }

        // 四角编辑控制
        binding.snapSwitch.setOnCheckedChangeListener { _, on -> binding.cornerView.setSnapEnabled(on) }
        setSnapLevel("mid")
        binding.snapWeak.setOnClickListener { setSnapLevel("weak") }
        binding.snapMid.setOnClickListener { setSnapLevel("mid") }
        binding.snapStrong.setOnClickListener { setSnapLevel("strong") }

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
            val bmp = ImageHolder.bitmap ?: return@setOnClickListener
            Thread {
                val mat = OpenCvEngine.bitmapToMat(bmp)
                val res = CornerDetector.detectRedCorners(mat, boardType)
                mat.release()
                runOnUiThread { binding.cornerView.setData(bmp, res.cornerPoints) }
            }.start()
        }

        binding.btnPrevStep.setOnClickListener { setStep(0) }
        binding.btnNextStep.setOnClickListener {
            ImageHolder.corners = binding.cornerView.getCorners()
            setStep(2)
            startActivity(Intent(this, ResultActivity::class.java))
        }

        setStep(0)
    }

    override fun onResume() {
        super.onResume()
        // 设置页可能改了板型/零点，回来时刷新信息条
        refreshInfoBar()
    }

    private fun refreshInfoBar() {
        boardType = AppSettings.getBoardType(this)
        val cfg = BoardPresets.get(boardType)
        val name = BoardPresets.displayNames.firstOrNull { it.first == boardType }?.second ?: boardType
        binding.infoBoardName.text = name
        binding.infoTubeCount.text = "（${cfg.tubeCount}管）"
        val zero = AppSettings.getZeroOffset(this)
        binding.infoZero.text = if (zero == zero.toLong().toDouble()) {
            "${zero.toInt()} mm"
        } else {
            "${"%.1f".format(zero)} mm"
        }
    }

    // ---------- 步骤指示器 ----------

    private fun setStep(step: Int) {
        currentStep = step
        val active = getColor(R.color.blue_primary)
        val inactive = Color.parseColor("#c9ced6")
        fun dot(v: View, on: Boolean) { v.backgroundTintList = ColorStateList.valueOf(if (on) active else inactive) }

        dot(binding.stepDot0, step >= 0)
        dot(binding.stepDot1, step >= 1)
        dot(binding.stepDot2, step >= 2)
        binding.stepDot0.backgroundTintList = ColorStateList.valueOf(active)
        binding.stepLabel0.setTextColor(if (step >= 0) active else inactive)
        binding.stepLabel1.setTextColor(if (step >= 1) active else inactive)
        binding.stepLabel2.setTextColor(if (step >= 2) active else inactive)
        binding.stepLine0.backgroundTintList = ColorStateList.valueOf(if (step >= 1) active else inactive)
        binding.stepLine1.backgroundTintList = ColorStateList.valueOf(if (step >= 2) active else inactive)

        binding.step0Body.visibility = if (step == 0) View.VISIBLE else View.GONE
        binding.step1Body.visibility = if (step == 1) View.VISIBLE else View.GONE
    }

    // ---------- 角标类型 ----------

    private fun setCornerMode(mode: String) {
        if (mode == "red_dot") {
            binding.modeRedDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.blue_primary))
            binding.modeRedDot.setTextColor(getColor(android.R.color.white))
            binding.modeAruco.strokeColor = ColorStateList.valueOf(getColor(R.color.text_hint))
            binding.modeAruco.setTextColor(getColor(R.color.text_sub))
            binding.modeTip.text = "使用设备四角的红色圆点标记（默认方案）"
        }
    }

    // ---------- 吸附强度 ----------

    private fun setSnapLevel(level: String) {
        binding.cornerView.setSnapLevel(level)
        val on = ColorStateList.valueOf(getColor(R.color.blue_primary))
        val off = ColorStateList.valueOf(getColor(R.color.text_hint))
        val sub = getColor(R.color.text_sub)
        binding.snapWeak.strokeColor = off
        binding.snapMid.strokeColor = off
        binding.snapStrong.strokeColor = off
        binding.snapWeak.setTextColor(sub)
        binding.snapMid.setTextColor(sub)
        binding.snapStrong.setTextColor(sub)
        when (level) {
            "weak" -> { binding.snapWeak.strokeColor = on; binding.snapWeak.setTextColor(getColor(R.color.blue_primary)) }
            "strong" -> { binding.snapStrong.strokeColor = on; binding.snapStrong.setTextColor(getColor(R.color.blue_primary)) }
            else -> { binding.snapMid.strokeColor = on; binding.snapMid.setTextColor(getColor(R.color.blue_primary)) }
        }
    }

    // ---------- 拍照 / 相册 ----------

    private fun ensureCameraPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == 100 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
    }

    private fun openCamera() {
        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraUri = uri
        cameraLauncher.launch(uri)
    }

    private fun loadBitmap(uri: Uri) {
        try {
            val src = if (Build.VERSION.SDK_INT >= 28) {
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(contentResolver, uri)
                ) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            val scaled = downscale(src, 2000)
            if (scaled !== src) src.recycle()

            ImageHolder.bitmap = scaled
            ImageHolder.corners = null
            ImageHolder.boardType = boardType

            binding.previewImage.setImageBitmap(scaled)
            showImageLoaded(true)
        } catch (e: Exception) {
            Toast.makeText(this, "图片加载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showImageLoaded(loaded: Boolean) {
        binding.previewImage.visibility = if (loaded) View.VISIBLE else View.GONE
        binding.previewPlaceholder.visibility = if (loaded) View.GONE else View.VISIBLE
        binding.actionNoImage.visibility = if (loaded) View.GONE else View.VISIBLE
        binding.actionHasImage.visibility = if (loaded) View.VISIBLE else View.GONE
    }

    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width; val h = src.height
        val scale = min(1.0, maxSide.toDouble() / max(w, h))
        if (scale >= 1.0) return src
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    // ---------- 四角识别 ----------

    private fun startCornerDetect() {
        val bmp = ImageHolder.bitmap ?: run {
            Toast.makeText(this, "请先拍照或从相册选择照片", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnStartDetect.isEnabled = false
        binding.btnStartDetect.text = "识别中..."
        Thread {
            val mat = OpenCvEngine.bitmapToMat(bmp)
            val res = CornerDetector.detectRedCorners(mat, boardType)
            mat.release()
            runOnUiThread {
                binding.btnStartDetect.isEnabled = true
                binding.btnStartDetect.text = "开始识别"
                ImageHolder.corners = res.cornerPoints
                binding.cornerView.setData(bmp, res.cornerPoints)
                setStep(1)
            }
        }.start()
    }
}
