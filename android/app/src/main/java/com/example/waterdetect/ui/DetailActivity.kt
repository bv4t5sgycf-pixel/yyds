package com.example.waterdetect.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.waterdetect.data.HistoryStore
import com.example.waterdetect.databinding.ActivityDetailBinding
import java.io.File

/** 历史详情页：结果图 + 统计 + 数据表。 */
class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private var recordId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recordId = intent.getLongExtra("id", -1)
        val rec = HistoryStore.get(this, recordId)
        if (rec == null) {
            Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        binding.detailTitle.text = "${rec.timeText} · ${rec.boardName}"
        binding.detailStats.text =
            "最大 ${"%.1f".format(rec.maxHeight)} mm · 最小 ${"%.1f".format(rec.minHeight)} mm · " +
            "平均 ${"%.1f".format(rec.avgHeight)} mm · CV ${"%.1f".format(rec.cv)}% · " +
            "成功率 ${"%.1f".format(rec.successRate)}% · 平滑窗口 ${rec.smoothWindow}"

        if (rec.imagePath.isNotBlank() && File(rec.imagePath).exists()) {
            val bmp = BitmapFactory.decodeFile(rec.imagePath)
            if (bmp != null) binding.detailImage.setImageBitmap(bmp)
        }

        val sb = StringBuilder()
        rec.heights.forEachIndexed { i, h ->
            sb.append("${i + 1}:${if (h == null) "-" else "%.1f".format(h)}    ")
            if ((i + 1) % 5 == 0) sb.append("\n")
        }
        binding.detailTable.text = sb.toString()

        binding.btnDelete.setOnClickListener {
            HistoryStore.delete(this, recordId)
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
