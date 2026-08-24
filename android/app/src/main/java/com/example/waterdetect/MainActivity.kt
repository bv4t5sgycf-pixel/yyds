package com.example.waterdetect

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.waterdetect.cv.OpenCvEngine
import com.example.waterdetect.data.AppSettings
import com.example.waterdetect.databinding.ActivityMainBinding
import com.example.waterdetect.databinding.ItemBoardBinding
import com.example.waterdetect.model.BoardPresets
import com.example.waterdetect.ui.HistoryActivity
import com.example.waterdetect.ui.CaptureActivity
import com.example.waterdetect.ui.TabNav
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 板型展示信息：key → (名称, 描述, 圆点颜色)（与小程序 index 页一致） */
    private val boardMeta = mapOf(
        "Board1008" to Triple("板型 1008", "84管 | 1008mm宽 | 12mm间距", R.color.board1008),
        "Board1200" to Triple("板型 1200", "100管 | 1200mm宽 | 12mm间距", R.color.board1200),
        "Board4000" to Triple("板型 4000", "200管 | 4000mm宽 | 20mm间距", R.color.blue_primary)
    )

    private var boardType: String = "Board1008"
    private val itemViews = mutableMapOf<String, ItemBoardBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!OpenCvEngine.ensureInitialized()) {
            Snackbar.make(binding.root, "OpenCV 初始化失败", Snackbar.LENGTH_LONG).show()
        }

        boardType = AppSettings.getBoardType(this)
        buildBoardList()
        selectBoard(boardType)

        binding.btnStart.setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
        }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        TabNav.bind(binding.bottomNav, R.id.nav_analysis)
    }

    override fun onResume() {
        super.onResume()
        // 设置页可能改了板型/零点，回来时刷新参数
        boardType = AppSettings.getBoardType(this)
        selectBoard(boardType)
    }

    private fun buildBoardList() {
        val order = listOf("Board1008", "Board1200", "Board4000")
        order.forEach { key ->
            val meta = boardMeta[key] ?: return@forEach
            val item = ItemBoardBinding.inflate(layoutInflater, binding.boardList, false)
            item.boardName.text = meta.first
            item.boardDesc.text = meta.second
            item.boardDot.setTextColor(getColor(meta.third))
            item.root.setOnClickListener {
                AppSettings.setBoardType(this, key)
                selectBoard(key)
            }
            binding.boardList.addView(item.root)
            itemViews[key] = item
        }
    }

    private fun selectBoard(key: String) {
        boardType = key
        itemViews.forEach { (k, v) ->
            val active = k == key
            v.root.background = getDrawable(
                if (active) R.drawable.bg_board_active else R.drawable.bg_board_normal
            )
            v.boardCheck.visibility = if (active) View.VISIBLE else View.GONE
        }
        val cfg = BoardPresets.get(key)
        binding.paramTubes.text = "${cfg.tubeCount} 根"
        binding.paramWidth.text = "${cfg.boardWmm} mm"
        binding.paramHeight.text = "${cfg.fullHeightMm} mm"
        val zero = AppSettings.getZeroOffset(this)
        binding.paramZero.text = if (zero == zero.toLong().toDouble()) {
            "${zero.toInt()} mm"
        } else {
            "${"%.1f".format(zero)} mm"
        }
    }
}
