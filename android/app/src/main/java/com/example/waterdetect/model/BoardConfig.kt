package com.example.waterdetect.model

/** 板型标定参数（与后端 detector.py / PWA analyzer.js 完全一致）。 */
data class BoardConfig(
    val boardWmm: Int,
    val fullHeightMm: Int,
    val tubeCount: Int
)

object BoardPresets {
    val BOARD1008 = BoardConfig(1008, 280, 84)
    val BOARD1200 = BoardConfig(1200, 280, 100)
    val BOARD4000 = BoardConfig(4000, 370, 200)

    fun get(type: String): BoardConfig = when (type) {
        "Board1008" -> BOARD1008
        "Board4000" -> BOARD4000
        else -> BOARD1200
    }

    val displayNames = listOf(
        "Board1008" to "板型 1008（84管）",
        "Board1200" to "板型 1200（100管）",
        "Board4000" to "板型 4000（200管）"
    )
}
