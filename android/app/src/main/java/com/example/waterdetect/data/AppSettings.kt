package com.example.waterdetect.data

import android.content.Context

/** 全局设置（SharedPreferences），供首页/采集/结果/设置页共享。 */
object AppSettings {
    private const val PREFS = "settings"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getBoardType(ctx: Context): String =
        prefs(ctx).getString("boardType", "Board1008") ?: "Board1008"

    fun setBoardType(ctx: Context, type: String) {
        prefs(ctx).edit().putString("boardType", type).apply()
    }

    fun getZeroOffset(ctx: Context): Double =
        (prefs(ctx).getString("zeroOffset", "0") ?: "0").toDoubleOrNull() ?: 0.0

    fun setZeroOffset(ctx: Context, value: Double) {
        prefs(ctx).edit().putString("zeroOffset", value.toString()).apply()
    }

    fun getSmoothWindow(ctx: Context): Int =
        prefs(ctx).getInt("smoothWindow", 1)

    fun setSmoothWindow(ctx: Context, w: Int) {
        prefs(ctx).edit().putInt("smoothWindow", w).apply()
    }
}
