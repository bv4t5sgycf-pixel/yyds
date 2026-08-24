package com.example.waterdetect.ui

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
import com.example.waterdetect.MainActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/** 三个 tab 页共用的底部导航逻辑。 */
object TabNav {
    fun bind(nav: BottomNavigationView, currentId: Int) {
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
