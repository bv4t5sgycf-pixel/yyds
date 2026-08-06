package com.example.waterdetect.cv

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * OpenCV 初始化与 Bitmap<->Mat 互转。
 * 原生 SDK 把 .so 直接打进 APK，这里只需 initLocal() 即可，无运行时下载。
 */
object OpenCvEngine {

    @Volatile
    private var initialized = false

    fun ensureInitialized(): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            val ok = try {
                OpenCVLoader.initLocal()
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
        val mat = Mat()
        Utils.bitmapToMat(bmp, mat)
        return mat
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }
}
