package com.example.dsdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * 닌텐도 DS 스타일 가상 패드 (D-패드, A/B/X/Y, L/R, START/SELECT, 메뉴).
 * 세로/가로 모두 대응. 게임 화면 영역(gameRect) 밖의 영역에 컨트롤을 배치한다.
 * 컨트롤이 아닌 곳을 터치하면 이벤트를 아래의 게임 뷰(터치스크린)로 넘긴다.
 */
class GamepadView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onKey(keyCode: Int, down: Boolean)
        fun onMenu()
    }

    var listener: Listener? = null
    var haptics = true

    private enum class Ctl(val keyCode: Int) {
        UP(KeyEvent.KEYCODE_DPAD_UP), DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
        LEFT(KeyEvent.KEYCODE_DPAD_LEFT), RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
        A(KeyEvent.KEYCODE_BUTTON_A), B(KeyEvent.KEYCODE_BUTTON_B),
        X(KeyEvent.KEYCODE_BUTTON_X), Y(KeyEvent.KEYCODE_BUTTON_Y),
        L(KeyEvent.KEYCODE_BUTTON_L1), R(KeyEvent.KEYCODE_BUTTON_R1),
        START(KeyEvent.KEYCODE_BUTTON_START), SELECT(KeyEvent.KEYCODE_BUTTON_SELECT)
    }

    private var pressed: EnumSet<Ctl> = EnumSet.noneOf(Ctl::class.java)
    private var gameRect = Rect()

    // ---- 레이아웃 값 ----
    private var dpX = 0f; private var dpY = 0f; private var dpR = 0f
    private var fcX = 0f; private var fcY = 0f; private var fcR = 0f
    private var btnR = 0f; private var btnOff = 0f
    private val lRect = RectF(); private val rRect = RectF()
    private val selRect = RectF(); private val staRect = RectF()
    private var menuX = 0f; private var menuY = 0f; private var menuR = 0f
    private val panels = ArrayList<RectF>()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    fun setGameRect(r: Rect) {
        gameRect = Rect(r)
        computeLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    private fun computeLayout() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (gameRect.isEmpty) gameRect = GameGeometry.gameRect(width, height, 0)
        panels.clear()
        if (h >= w) {
            val top = gameRect.bottom.toFloat()
            val zh = h - top
            panels.add(RectF(0f, top, w, h))
            dpR = min(w * 0.19f, zh * 0.27f)
            dpX = w * 0.26f; dpY = top + zh * 0.56f
            fcR = dpR * 0.95f
            fcX = w * 0.74f; fcY = dpY
            lRect.set(w * 0.05f, top + zh * 0.04f, w * 0.31f, top + zh * 0.15f)
            rRect.set(w * 0.69f, top + zh * 0.04f, w * 0.95f, top + zh * 0.15f)
            menuX = w * 0.5f; menuY = top + zh * 0.10f; menuR = min(w * 0.05f, zh * 0.055f)
            val pw = w * 0.17f; val ph = zh * 0.065f; val py = h - zh * 0.11f
            selRect.set(w * 0.36f - pw / 2, py - ph / 2, w * 0.36f + pw / 2, py + ph / 2)
            staRect.set(w * 0.64f - pw / 2, py - ph / 2, w * 0.64f + pw / 2, py + ph / 2)
        } else {
            val lz = gameRect.left.toFloat()
            val rx = gameRect.right.toFloat()
            val rz = w - rx
            panels.add(RectF(0f, 0f, lz, h))
            panels.add(RectF(rx, 0f, w, h))
            dpR = min(min(lz, rz) * 0.36f, h * 0.24f)
            dpX = lz * 0.5f; dpY = h * 0.56f
            fcR = dpR * 0.95f
            fcX = rx + rz * 0.5f; fcY = dpY
            lRect.set(lz * 0.12f, h * 0.06f, lz * 0.62f, h * 0.17f)
            rRect.set(rx + rz * 0.38f, h * 0.06f, rx + rz * 0.88f, h * 0.17f)
            val pw = min(lz * 0.34f, h * 0.2f); val ph = h * 0.07f; val py = h * 0.88f
            selRect.set(lz * 0.30f - pw / 2, py - ph / 2, lz * 0.30f + pw / 2, py + ph / 2)
            staRect.set(rx + rz * 0.70f - pw / 2, py - ph / 2, rx + rz * 0.70f + pw / 2, py + ph / 2)
            menuX = lz * 0.74f; menuY = py; menuR = min(lz * 0.09f, h * 0.05f)
        }
        btnR = fcR * 0.34f
        btnOff = fcR * 0.62f
    }

    // ---- 터치 ----
    private fun inRect(r: RectF, x: Float, y: Float, pad: Float) =
        x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad

    private fun hit(x: Float, y: Float): EnumSet<Ctl> {
        val s = EnumSet.noneOf(Ctl::class.java)
        val pad = context.dpf(6f)
        // D-패드 (8방향)
        val dx = x - dpX; val dy = y - dpY
        val dist = hypot(dx, dy)
        if (dist <= dpR * 1.3f && dist > dpR * 0.18f) {
            val ang = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() // 0=오른쪽, 90=아래
            val right = abs(ang) < 67.5f
            val left = abs(ang) > 112.5f
            val down = ang in 22.5f..157.5f
            val up = ang in -157.5f..-22.5f
            if (right) s.add(Ctl.RIGHT)
            if (left) s.add(Ctl.LEFT)
            if (down) s.add(Ctl.DOWN)
            if (up) s.add(Ctl.UP)
        }
        // 페이스 버튼
        fun near(cx: Float, cy: Float) = hypot(x - cx, y - cy) <= btnR * 1.4f
        if (near(fcX + btnOff, fcY)) s.add(Ctl.A)
        if (near(fcX, fcY + btnOff)) s.add(Ctl.B)
        if (near(fcX, fcY - btnOff)) s.add(Ctl.X)
        if (near(fcX - btnOff, fcY)) s.add(Ctl.Y)
        if (inRect(lRect, x, y, pad)) s.add(Ctl.L)
        if (inRect(rRect, x, y, pad)) s.add(Ctl.R)
        if (inRect(selRect, x, y, pad)) s.add(Ctl.SELECT)
        if (inRect(staRect, x, y, pad)) s.add(Ctl.START)
        return s
    }

    private fun hitMenu(x: Float, y: Float) = hypot(x - menuX, y - menuY) <= menuR * 1.5f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val act = e.actionMasked
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            val x = e.getX(e.actionIndex); val y = e.getY(e.actionIndex)
            if (hitMenu(x, y)) { listener?.onMenu(); return true }
            if (hit(x, y).isEmpty() && pressed.isEmpty()) return false // 게임(터치스크린)으로 전달
        }
        val up = act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL
        val lift = if (act == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        val now = EnumSet.noneOf(Ctl::class.java)
        if (!up) for (i in 0 until e.pointerCount) {
            if (i == lift) continue
            now.addAll(hit(e.getX(i), e.getY(i)))
        }
        for (c in Ctl.values()) {
            val was = pressed.contains(c)
            val isNow = now.contains(c)
            if (was != isNow) {
                listener?.onKey(c.keyCode, isNow)
                if (isNow && haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
        pressed = now
        invalidate()
        return true
    }

    /** 앱이 백그라운드로 갈 때 눌린 키 해제 */
    fun releaseAll() {
        for (c in pressed) listener?.onKey(c.keyCode, false)
        pressed = EnumSet.noneOf(Ctl::class.java)
        invalidate()
    }

    // ---- 그리기 ----
    override fun onDraw(c: Canvas) {
        // 본체 패널
        for (r in panels) {
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF23243A.toInt(), 0xFF0C0C16.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(r, p)
            p.shader = null
        }
        if (height >= width) {
            p.color = 0xFF3A3C5C.toInt(); p.strokeWidth = context.dpf(2f); p.style = Paint.Style.STROKE
            c.drawLine(0f, gameRect.bottom.toFloat(), width.toFloat(), gameRect.bottom.toFloat(), p)
            p.style = Paint.Style.FILL
        }
        drawDpad(c)
        drawFace(c)
        drawShoulder(c, lRect, "L", pressed.contains(Ctl.L))
        drawShoulder(c, rRect, "R", pressed.contains(Ctl.R))
        drawPill(c, selRect, "SELECT", pressed.contains(Ctl.SELECT))
        drawPill(c, staRect, "START", pressed.contains(Ctl.START))
        drawMenu(c)
    }

    private fun drawDpad(c: Canvas) {
        // 바깥 링
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(dpX, dpY, dpR * 1.15f, 0xFF2E3050.toInt(), 0xFF14152A.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(dpX, dpY, dpR * 1.12f, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(2f); p.color = 0xFF4A4D78.toInt()
        c.drawCircle(dpX, dpY, dpR * 1.12f, p)
        p.style = Paint.Style.FILL
        // 십자 모양
        val arm = dpR * 0.34f
        val len = dpR * 0.98f
        val cross = Path().apply {
            addRoundRect(RectF(dpX - len, dpY - arm, dpX + len, dpY + arm), arm * 0.5f, arm * 0.5f, Path.Direction.CW)
            addRoundRect(RectF(dpX - arm, dpY - len, dpX + arm, dpY + len), arm * 0.5f, arm * 0.5f, Path.Direction.CW)
        }
        p.shader = LinearGradient(dpX, dpY - len, dpX, dpY + len, 0xFF5A5E8C.toInt(), 0xFF2A2C48.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(cross, p)
        p.shader = null
        // 눌림 + 화살표
        fun arrow(dx: Float, dy: Float, on: Boolean) {
            val cx = dpX + dx * len * 0.68f; val cy = dpY + dy * len * 0.68f
            val s = arm * 0.55f
            val path = Path()
            if (dx != 0f) { // 좌우
                path.moveTo(cx + dx * s, cy); path.lineTo(cx - dx * s, cy - s); path.lineTo(cx - dx * s, cy + s)
            } else {
                path.moveTo(cx, cy + dy * s); path.lineTo(cx - s, cy - dy * s); path.lineTo(cx + s, cy - dy * s)
            }
            path.close()
            if (on) { p.color = 0x88FFFFFF.toInt(); c.drawCircle(cx, cy, arm * 0.95f, p) }
            p.color = if (on) 0xFFFFFFFF.toInt() else 0xFFB8BBE0.toInt()
            c.drawPath(path, p)
        }
        arrow(0f, -1f, pressed.contains(Ctl.UP))
        arrow(0f, 1f, pressed.contains(Ctl.DOWN))
        arrow(-1f, 0f, pressed.contains(Ctl.LEFT))
        arrow(1f, 0f, pressed.contains(Ctl.RIGHT))
        p.color = 0xFF1A1B30.toInt()
        c.drawCircle(dpX, dpY, arm * 0.45f, p)
    }

    private fun drawFace(c: Canvas) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(fcX, fcY, fcR * 1.3f, 0xFF2E3050.toInt(), 0xFF14152A.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(fcX, fcY, fcR * 1.25f, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(2f); p.color = 0xFF4A4D78.toInt()
        c.drawCircle(fcX, fcY, fcR * 1.25f, p)
        p.style = Paint.Style.FILL
        face(c, fcX + btnOff, fcY, "A", 0xFFE63946.toInt(), pressed.contains(Ctl.A))
        face(c, fcX, fcY + btnOff, "B", 0xFFF4B400.toInt(), pressed.contains(Ctl.B))
        face(c, fcX, fcY - btnOff, "X", 0xFF3A86FF.toInt(), pressed.contains(Ctl.X))
        face(c, fcX - btnOff, fcY, "Y", 0xFF2EC4B6.toInt(), pressed.contains(Ctl.Y))
    }

    private fun face(c: Canvas, x: Float, y: Float, label: String, color: Int, on: Boolean) {
        val r = if (on) btnR * 0.92f else btnR
        p.style = Paint.Style.FILL
        p.color = 0x55000000
        c.drawCircle(x + r * 0.08f, y + r * 0.12f, r, p)
        val light = if (on) color else blend(color, Color.WHITE, 0.35f)
        val dark = if (on) blend(color, Color.BLACK, 0.35f) else blend(color, Color.BLACK, 0.2f)
        p.shader = RadialGradient(x - r * 0.3f, y - r * 0.35f, r * 1.4f, light, dark, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1.5f); p.color = 0x66FFFFFF
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.FILL
        text.color = Color.WHITE
        text.textSize = r * 0.95f
        c.drawText(label, x, y + text.textSize * 0.34f, text)
    }

    private fun drawShoulder(c: Canvas, r: RectF, label: String, on: Boolean) {
        p.style = Paint.Style.FILL
        val top = if (on) 0xFF8A8FD0.toInt() else 0xFF5A5E8C.toInt()
        val bot = if (on) 0xFF4A4F90.toInt() else 0xFF2A2C48.toInt()
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bot, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, r.height() * 0.45f, r.height() * 0.45f, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1.5f); p.color = 0xFF8D91C4.toInt()
        c.drawRoundRect(r, r.height() * 0.45f, r.height() * 0.45f, p)
        p.style = Paint.Style.FILL
        text.color = 0xFFEDEEFF.toInt(); text.textSize = r.height() * 0.55f
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
    }

    private fun drawPill(c: Canvas, r: RectF, label: String, on: Boolean) {
        p.style = Paint.Style.FILL
        p.color = if (on) 0xFF8A8FD0.toInt() else 0xFF3B3E66.toInt()
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1.2f); p.color = 0xFF7E82B8.toInt()
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.style = Paint.Style.FILL
        text.color = 0xFFEDEEFF.toInt(); text.textSize = r.height() * 0.42f
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
    }

    private fun drawMenu(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = 0xFF3B3E66.toInt()
        c.drawCircle(menuX, menuY, menuR, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1.2f); p.color = 0xFF7E82B8.toInt()
        c.drawCircle(menuX, menuY, menuR, p)
        p.style = Paint.Style.FILL; p.color = 0xFFEDEEFF.toInt()
        val w = menuR * 0.9f; val t = menuR * 0.14f
        for (i in -1..1) c.drawRoundRect(RectF(menuX - w / 2, menuY + i * menuR * 0.34f - t, menuX + w / 2, menuY + i * menuR * 0.34f + t), t, t, p)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }
}
