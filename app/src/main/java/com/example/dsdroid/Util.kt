package com.example.dsdroid

import android.content.Context
import android.graphics.Rect

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

/** DS 두 화면을 위/아래로 쌓으면 256x384 (2:3) 비율. */
object GameGeometry {
    fun gameRect(w: Int, h: Int, topInset: Int): Rect {
        return if (h >= w) {
            // 세로: 화면 위쪽에 게임, 아래쪽에 패드
            var gh = minOf(1.5f * w, 0.62f * h)
            gh = minOf(gh, (h - topInset).toFloat())
            val gw = gh / 1.5f
            val left = ((w - gw) / 2f).toInt()
            Rect(left, topInset, (left + gw).toInt(), topInset + gh.toInt())
        } else {
            // 가로: 게임을 가운데에 크게, 좌우에 패드
            val gh = h.toFloat()
            val gw = minOf(gh / 1.5f, w.toFloat())
            val left = ((w - gw) / 2f).toInt()
            Rect(left, 0, (left + gw).toInt(), h)
        }
    }
}
