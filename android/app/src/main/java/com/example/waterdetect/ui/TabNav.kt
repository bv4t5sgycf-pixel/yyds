package com.example.waterdetect.ui

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
import com.example.waterdetect.MainActivity
import com.example.waterdetect.databinding.ViewBottomNavBinding
import com.google.android.material.bottomnavigation.BottomNavigationView

/** 三个 tab 页共用的底部导航逻辑。 */
object TabNav {
    // 注意：布局里 <include android:id="@+id/bottomNav"> 使 ViewBinding 生成
    // ViewBottomNavBinding 类型（而非 BottomNavigationView 视图本身），
    // 其 root 即 view_bottom_nav.xml 的根视图 BottomNavigationView。
    fun bind(navBinding: ViewBottomNavBinding, currentId: Int) {
        val nav = navBinding.root as BottomNavigationView
        nav.selectedItemId = currentId
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == currentId) return@setOnItemSelectedListener true
            val ctx = nav.context
            val target = when (item.itemId) {
                com.example.waterdetect.R.id.nav_history -> HistoryActivity::class.java
                com.example.waterdetect.R.id.nav_settings -> SettingsActivity::class.java
                else -> MainActivity::class.java
            }
            ctx.startActivity(Intent(ctx, target).addFlags(FLAG_ACTIVITY_REORDER_TO_FRONT))
            true
        }
    }
}
