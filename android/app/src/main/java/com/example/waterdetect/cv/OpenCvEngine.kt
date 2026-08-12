package com.example.waterdetect.cv

import android.graphics.Bitmap
import android.util.Log
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.global.opencv_core.CV_8UC4
import org.bytedeco.opencv.global.opencv_imgproc.Imgproc
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2BGR
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2BGRA

/**
 * OpenCV 初始化与 Bitmap<->Mat 互转（bytedeco 完整构建）。
 * - Loader.load 在运行时把对应 ABI 的原生 so 从 APK 提取并加载，无运行时下载、可离线。
 * - bytedeco 不提供兼容自身 Mat 的 Bitmap 工具类，故这里手动做 BGRA/ARGB 像素互转。
 * 内部 Mat 一律为 3 通道 BGR；Bitmap 经 BGRA 中转。
 */
object OpenCvEngine {

    @Volatile
    private var initialized = false

    fun ensureInitialized(): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            val ok = try {
                Loader.load(org.bytedeco.opencv.opencv_java::class.java)
                true
            } catch (e: Exception) {
                Log.e("OpenCvEngine", "OpenCV init failed", e)
                false
            }
            initialized = ok
            return ok
        }
    }

    fun isInitialized(): Boolean = initialized

    fun bitmapToMat(bmp: Bitmap): Mat {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgba = Mat(h, w, CV_8UC4)
        val buf = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            val o = i * 4
            buf[o] = (p and 0xFF).toByte()
            buf[o + 1] = ((p shr 8) and 0xFF).toByte()
            buf[o + 2] = ((p shr 16) and 0xFF).toByte()
            buf[o + 3] = ((p shr 24) and 0xFF).toByte()
        }
        rgba.put(0, 0, buf)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, COLOR_BGRA2BGR)
        rgba.release()
        return bgr
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val w = mat.cols()
        val h = mat.rows()
        val rgba = Mat()
        Imgproc.cvtColor(mat, rgba, COLOR_BGR2BGRA)
        val buf = ByteArray(w * h * 4)
        rgba.put(0, 0, buf)
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            val o = i * 4
            val b = buf[o].toInt() and 0xFF
            val g = buf[o + 1].toInt() and 0xFF
            val r = buf[o + 2].toInt() and 0xFF
            val a = buf[o + 3].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        rgba.release()
        return bmp
    }
}
