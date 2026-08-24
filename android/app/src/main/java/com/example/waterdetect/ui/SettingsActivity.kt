package com.example.waterdetect.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.waterdetect.R
import com.example.waterdetect.data.AppSettings
import com.example.waterdetect.data.HistoryStore
import com.example.waterdetect.databinding.ActivitySettingsBinding
import com.example.waterdetect.model.BoardPresets
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

/** 设置 tab：零点修正 / 默认平滑档 / 关于 / 清空历史（对齐小程序设置页）。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsBoard.text =
            BoardPresets.displayNames.firstOrNull { it.first == AppSettings.getBoardType(this) }?.second
                ?: "板型 1008（84管）"

        binding.zeroInput.setText(AppSettings.getZeroOffset(this).toString())
        binding.btnSaveZero.setOnClickListener {
            val v = binding.zeroInput.text.toString().toDoubleOrNull()
            if (v == null) {
                Toast.makeText(this, "请输入有效数值", Toast.LENGTH_SHORT).show()
            } else {
                AppSettings.setZeroOffset(this, v)
                Toast.makeText(this, "零点修正已保存：$v mm", Toast.LENGTH_SHORT).show()
            }
        }

        setupSmoothButtons()

        binding.btnClearHistory.setOnClickListener { v ->
            val n = HistoryStore.count(this)
            if (n == 0) {
                Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show()
            } else {
                HistoryStore.clearAll(this)
                Snackbar.make(v, "已清空 $n 条历史记录", Snackbar.LENGTH_SHORT).show()
            }
        }

        TabNav.bind(binding.bottomNav, R.id.nav_settings)
    }

    private fun setupSmoothButtons() {
        val map = listOf(
            binding.sSmooth1 to 1, binding.sSmooth3 to 3, binding.sSmooth5 to 5,
            binding.sSmooth7 to 7, binding.sSmooth9 to 9
        )
        val blue = ContextCompat.getColor(this, R.color.blue_primary)
        val gray = ContextCompat.getColor(this, R.color.text_sub)
        fun highlight(sel: MaterialButton) {
            map.forEach { (btn, _) ->
                btn.strokeColor = android.content.res.ColorStateList.valueOf(if (btn === sel) blue else gray)
            }
        }
        val current = map.firstOrNull { it.second == AppSettings.getSmoothWindow(this) } ?: map.first()
        highlight(current.first)
        map.forEach { (btn, lvl) ->
            btn.setOnClickListener {
                AppSettings.setSmoothWindow(this, lvl)
                highlight(btn)
                Toast.makeText(this, "默认平滑窗口：$lvl", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
