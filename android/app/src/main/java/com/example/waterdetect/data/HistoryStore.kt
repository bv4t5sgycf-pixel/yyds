package com.example.waterdetect.data

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地历史记录：每条记录一个 JSON 文件 + 一张结果图（JPEG），存于 filesDir/history/。
 * 零第三方依赖（org.json 为 Android 内置），保留最近 50 条。
 */
object HistoryStore {

    data class Record(
        val id: Long,
        val timeText: String,
        val boardType: String,
        val boardName: String,
        val tubeCount: Int,
        val maxHeight: Double,
        val minHeight: Double,
        val avgHeight: Double,
        val cv: Double,
        val successRate: Double,
        val smoothWindow: Int,
        val heights: List<Double?>,
        val imagePath: String
    )

    private const val MAX_RECORDS = 50

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "history").apply { if (!exists()) mkdirs() }

    fun save(ctx: Context, boardType: String, boardName: String, tubeCount: Int,
             maxHeight: Double, minHeight: Double, avgHeight: Double, cv: Double,
             successRate: Double, smoothWindow: Int, heights: List<Double?>,
             resultBitmap: Bitmap?): Long {
        val id = System.currentTimeMillis()
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())

        // 保存结果图
        var imagePath = ""
        if (resultBitmap != null) {
            val img = File(dir(ctx), "$id.jpg")
            try {
                img.outputStream().use { out ->
                    resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                imagePath = img.absolutePath
            } catch (_: Exception) { /* 图片保存失败不影响记录 */ }
        }

        // 保存 JSON
        val arr = JSONArray()
        heights.forEach { h -> arr.put(h ?: JSONObject.NULL) }
        val json = JSONObject().apply {
            put("id", id)
            put("timeText", timeText)
            put("boardType", boardType)
            put("boardName", boardName)
            put("tubeCount", tubeCount)
            put("max", maxHeight)
            put("min", minHeight)
            put("avg", avgHeight)
            put("cv", cv)
            put("successRate", successRate)
            put("smoothWindow", smoothWindow)
            put("heights", arr)
            put("imagePath", imagePath)
        }
        File(dir(ctx), "$id.json").writeText(json.toString())

        trim(ctx)
        return id
    }

    fun list(ctx: Context): List<Record> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
            ?.sortedByDescending { it.id }
            ?: emptyList()

    fun get(ctx: Context, id: Long): Record? {
        val f = File(dir(ctx), "$id.json")
        return if (f.exists()) runCatching { parse(f.readText()) }.getOrNull() else null
    }

    fun delete(ctx: Context, id: Long) {
        val f = File(dir(ctx), "$id.json")
        val rec = runCatching { parse(f.readText()) }.getOrNull()
        f.delete()
        rec?.imagePath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
    }

    fun clearAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    fun count(ctx: Context): Int =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.nameWithoutExtension.toLongOrNull() ?: 0 }
            ?: return
        if (files.size <= MAX_RECORDS) return
        files.drop(MAX_RECORDS).forEach { f ->
            val rec = runCatching { parse(f.readText()) }.getOrNull()
            f.delete()
            rec?.imagePath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        }
    }

    private fun parse(text: String): Record {
        val j = JSONObject(text)
        val arr = j.getJSONArray("heights")
        val heights = (0 until arr.length()).map { idx ->
            if (arr.isNull(idx)) null else arr.getDouble(idx)
        }
        return Record(
            id = j.getLong("id"),
            timeText = j.optString("timeText", ""),
            boardType = j.optString("boardType", ""),
            boardName = j.optString("boardName", ""),
            tubeCount = j.optInt("tubeCount", 0),
            maxHeight = j.optDouble("max", 0.0),
            minHeight = j.optDouble("min", 0.0),
            avgHeight = j.optDouble("avg", 0.0),
            cv = j.optDouble("cv", 0.0),
            successRate = j.optDouble("successRate", 0.0),
            smoothWindow = j.optInt("smoothWindow", 1),
            heights = heights,
            imagePath = j.optString("imagePath", "")
        )
    }
}
