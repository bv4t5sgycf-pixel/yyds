package com.example.waterdetect.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.waterdetect.R
import com.example.waterdetect.data.HistoryStore
import com.example.waterdetect.databinding.ActivityHistoryBinding
import com.example.waterdetect.databinding.ItemHistoryBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar

/** 记录 tab：本地历史列表（可按板型筛选，点击进详情，可单条删除）。 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private var filter = "all"
    private var records: List<HistoryStore.Record> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFilters()
        TabNav.bind(binding.bottomNav, R.id.nav_history)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun setupFilters() {
        val map = listOf(
            binding.filterAll to "all",
            binding.filter4000 to "Board4000",
            binding.filter1200 to "Board1200",
            binding.filter1008 to "Board1008"
        )
        val blue = ContextCompat.getColor(this, R.color.blue_primary)
        val gray = ContextCompat.getColor(this, R.color.text_sub)
        fun highlight(sel: MaterialButton) {
            map.forEach { (btn, _) ->
                btn.strokeColor = android.content.res.ColorStateList.valueOf(if (btn === sel) blue else gray)
            }
        }
        map.forEach { (btn, key) ->
            btn.setOnClickListener { filter = key; highlight(btn); reload() }
        }
        highlight(binding.filterAll)
    }

    private fun reload() {
        records = HistoryStore.list(this)
        val shown = if (filter == "all") records else records.filter { it.boardType == filter }

        binding.historyList.removeAllViews()
        binding.emptyText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE

        shown.forEach { rec ->
            val item = ItemHistoryBinding.inflate(layoutInflater, binding.historyList, false)
            item.historyTime.text = rec.timeText
            item.historyBoard.text = rec.boardName
            item.historyCv.text = "CV: ${"%.1f".format(rec.cv)}%"
            item.historyCv.setTextColor(
                ContextCompat.getColor(this, if (rec.cv > 15) R.color.danger else R.color.success)
            )
            item.historyStats.text =
                "Avg: ${"%.1f".format(rec.avgHeight)} mm | Max: ${"%.1f".format(rec.maxHeight)} mm"

            item.root.setOnClickListener {
                startActivity(Intent(this, DetailActivity::class.java).putExtra("id", rec.id))
            }
            item.historyDelete.setOnClickListener { v ->
                HistoryStore.delete(this, rec.id)
                Snackbar.make(v, "已删除该记录", Snackbar.LENGTH_SHORT).show()
                reload()
            }

            val card = MaterialCardView(this).apply {
                radius = 12f
                strokeWidth = 0
                setCardBackgroundColor(
                    android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.card)
                    )
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 10 }
            }
            card.addView(item.root)
            binding.historyList.addView(card)
        }
    }
}
