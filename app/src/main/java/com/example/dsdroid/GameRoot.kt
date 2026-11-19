package com.example.dsdroid

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** 게임 뷰를 세로/가로에 맞는 위치에 놓고, 가상 패드를 전체 화면에 덮는 레이아웃. */
class GameRoot(ctx: Context, private val game: View, private val pad: GamepadView) : ViewGroup(ctx) {

    var topInset: Int = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    init {
        addView(game)
        addView(pad)
        clipChildren = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return
        val rect = GameGeometry.gameRect(w, h, if (h >= w) topInset else 0)
        game.measure(
            MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY)
        )
        game.layout(rect.left, rect.top, rect.right, rect.bottom)
        pad.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        pad.layout(0, 0, w, h)
        pad.setGameRect(rect)
    }
}
