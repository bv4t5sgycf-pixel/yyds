package com.example.waterdetect.cv

import android.graphics.Bitmap
import android.util.Log
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core.Mat
import org.bytedeco.opencv.global.opencv_core.Imgproc
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2BGR
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2BGRA
import org.bytedeco.opencv.android.Utils

/**
 * OpenCV 初始化与 Bitmap<->Mat 互转。
 * 使用 bytedeco 完整构建：Loader.load 在运行时把对应 ABI 的原生 so 从 APK 提取并加载，
 * 无运行时下载、可离线。
 * 统一约定：内部 Mat 一律为 3 通道 BGR（与其余检测算法一致）；
 * Bitmap 经 BGRA 中转，规避 Android 与 OpenCV 通道顺序差异。
 */
object OpenCvEngine {

    @Volatile
    private var initialized = false

    fun ensureInitialized(): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            val ok = try {
                Loader.load(org.bytedeco.opencv.global.opencv_java::class.java)
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
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, COLOR_BGRA2BGR)
        rgba.release()
        return bgr
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(mat, rgba, COLOR_BGR2BGRA)
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp
    }
}
