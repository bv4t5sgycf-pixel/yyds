package com.example.waterdetect

import android.graphics.Bitmap

/** 跨 Activity 传递大图与四角（避免 Intent 序列化 1MB 限制）。 */
object ImageHolder {
    var bitmap: Bitmap? = null
    var corners: List<com.example.waterdetect.cv.CornerDetector.CornerPoint>? = null
    var boardType: String = "Board1200"
}
