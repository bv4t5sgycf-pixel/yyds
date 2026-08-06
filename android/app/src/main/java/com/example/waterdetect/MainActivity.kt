package com.example.waterdetect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import kotlin.math.max
import kotlin.math.min
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.waterdetect.cv.CornerDetector
import com.example.waterdetect.cv.OpenCvEngine
import com.example.waterdetect.model.BoardPresets
import com.example.waterdetect.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var boardType = "Board1200"
    private var cameraUri: Uri? = null

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadBitmap(it) }
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { loadBitmap(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!OpenCvEngine.ensureInitialized()) {
            Snackbar.make(binding.root, "OpenCV 初始化失败", Snackbar.LENGTH_LONG).show()
        }

        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            BoardPresets.displayNames.map { it.second }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.boardSpinner.adapter = adapter
        binding.boardSpinner.setSelection(1) // Board1200 默认
        binding.boardSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                boardType = BoardPresets.displayNames[pos].first
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }

        binding.btnCapture.setOnClickListener { ensureCameraPermission { openCamera() } }
        binding.btnPick.setOnClickListener { pickLauncher.launch("image/*") }
    }

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
        cameraUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraLauncher.launch(cameraUri)
    }

    private fun loadBitmap(uri: Uri) {
        try {
            val src = if (Build.VERSION.SDK_INT >= 28) {
                val src2 = android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(contentResolver, uri)
                ) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                src2
            } else {
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            val scaled = downscale(src, 2000)
            src.recycle()

            val mat = OpenCvEngine.bitmapToMat(scaled)
            val res = CornerDetector.detectRedCorners(mat, boardType)
            mat.release()

            ImageHolder.bitmap = scaled
            ImageHolder.corners = res.cornerPoints
            ImageHolder.boardType = boardType
            startActivity(Intent(this, CaptureActivity::class.java))
        } catch (e: Exception) {
            Snackbar.make(binding.root, "图片加载失败：${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width; val h = src.height
        val scale = min(1.0, maxSide.toDouble() / max(w, h))
        if (scale >= 1.0) return src
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }
}
