package com.example.dsdroid

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class GameActivity : AppCompatActivity(), GamepadView.Listener {

    companion object {
        const val EXTRA_ROM = "rom"
        // 워크플로가 받아 넣는 코어: jniLibs/<abi>/libdesmume_libretro_android.so
        const val CORE_LIB = "libdesmume_libretro_android.so"
    }

    private lateinit var store: Store
    private lateinit var info: RomInfo
    private lateinit var retroView: GLRetroView
    private lateinit var pad: GamepadView
    private lateinit var root: GameRoot

    private var ready = false
    private var frames = 0
    private var cheatGame: CheatGame? = null
    private val enabledCheats = sortedSetOf<Int>()
    private var fast = false
    private var muted = false
    private val handler = Handler(Looper.getMainLooper())

    private val flush = object : Runnable {
        override fun run() {
            if (ready) saveSram()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val romPath = intent.getStringExtra(EXTRA_ROM)
        val rom = romPath?.let { File(it) }
        val parsed = rom?.let { NdsHeader.read(it) }
        if (rom == null || parsed == null) {
            Toast.makeText(this, "롬 파일을 읽을 수 없습니다", Toast.LENGTH_LONG).show(); finish(); return
        }
        info = parsed

        val coreFile = File(applicationInfo.nativeLibraryDir, CORE_LIB)
        if (!coreFile.exists()) {
            AlertDialog.Builder(this)
                .setTitle("에뮬레이터 코어 없음")
                .setMessage("$CORE_LIB 파일이 APK에 포함되지 않았습니다.\nGitHub Actions 의 'Download core' 단계가 성공했는지 확인하세요.")
                .setPositiveButton("확인") { _, _ -> finish() }
                .setCancelable(false).show()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupSystemBars()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        val data = GLRetroViewData(this).apply {
            coreFilePath = CORE_LIB
            gameFilePath = rom.absolutePath
            systemDirectory = store.systemDir.absolutePath
            savesDirectory = store.coreSaveDir(info.key).absolutePath
            variables = arrayOf(
                Variable("desmume_screens_layout", "top/bottom"),
                Variable("desmume_pointer_type", "touch")
            )
            val sram = store.sramFile(info.key)
            if (sram.isFile) saveRAMState = sram.readBytes()
            rumbleEventsEnabled = false
            preferLowLatencyAudio = false
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        pad = GamepadView(this).apply { listener = this@GameActivity }
        root = GameRoot(this, retroView, pad)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.topInset = i.top
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { showMenu() }
        })

        lifecycleScope.launch {
            retroView.getGLRetroEvents().collect { ev ->
                if (ev is GLRetroView.GLRetroEvents.FrameRendered && !ready) {
                    frames++
                    if (frames == 3) onCoreReady()
                }
            }
        }
        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { code ->
                Toast.makeText(this@GameActivity, "코어 오류 (코드 $code)", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------------------------------------------------------------- 준비/저장

    private fun onCoreReady() {
        ready = true
        // 자동 이어하기
        val auto = store.stateFile(info.key, 0)
        if (store.autoResume && auto.isFile) {
            try { retroView.unserializeState(auto.readBytes()) } catch (_: Throwable) {}
        }
        // 치트 로드(백그라운드)
        lifecycleScope.launch {
            val g = withContext(Dispatchers.IO) {
                R4CheatDb.load(store.cheatDbFile)?.let { R4CheatDb.findGame(it, info) }
            }
            cheatGame = g
            val sel = store.cheatSelFile(info.key)
            if (g != null && sel.isFile) {
                sel.readText().split(',').mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in g.items.indices }.forEach { enabledCheats.add(it) }
                applyCheats()
            }
        }
        handler.postDelayed(flush, 30_000)
    }

    private fun saveSram(): Boolean {
        return try {
            val b = retroView.serializeSRAM()
            if (b.isNotEmpty()) { store.writeAtomic(store.sramFile(info.key), b); true } else false
        } catch (_: Throwable) { false }
    }

    private fun saveState(slot: Int): Boolean {
        return try {
            val b = retroView.serializeState()
            if (b.isNotEmpty()) { store.writeAtomic(store.stateFile(info.key, slot), b); true } else false
        } catch (_: Throwable) { false }
    }

    private fun loadState(slot: Int): Boolean {
        val f = store.stateFile(info.key, slot)
        if (!f.isFile) return false
        return try { retroView.unserializeState(f.readBytes()) } catch (_: Throwable) { false }
    }

    override fun onPause() {
        // 주의: super.onPause() 이후엔 GL 스레드가 멈추므로 반드시 그 전에 저장한다.
        if (::retroView.isInitialized && ready) {
            pad.releaseAll()
            saveSram()
            saveState(0) // 자동 이어하기용
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 입력

    override fun onKey(keyCode: Int, down: Boolean) {
        retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0)
    }

    override fun onMenu() { showMenu() }

    private val mappedKeys = setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
    )

    /** 블루투스/USB 게임패드 지원 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromPad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (fromPad && ::retroView.isInitialized) {
            if (event.keyCode in mappedKeys) {
                retroView.sendKeyEvent(event.action, event.keyCode, 0); return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (event.action == KeyEvent.ACTION_UP) showMenu(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- 메뉴

    private fun slotLabel(prefix: String, slot: Int): String {
        val f = store.stateFile(info.key, slot)
        val t = if (f.isFile) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.lastModified())) else "비어 있음"
        return "$prefix $slot  ($t)"
    }

    private fun showMenu() {
        if (!ready) return
        pad.releaseAll()
        val items = arrayOf(
            "▶  계속하기",
            "💾  진행 상황 저장 (스테이트)",
            "📂  진행 상황 불러오기 (스테이트)",
            "🎮  게임 데이터 저장 (SRAM)",
            "🎮  게임 데이터 다시 불러오기 (SRAM)",
            "🧪  치트 설정",
            if (fast) "⏩  배속: 2배 (끄기)" else "⏩  배속: 끔 (2배로)",
            if (muted) "🔇  소리: 끔 (켜기)" else "🔊  소리: 켬 (끄기)",
            "🔄  리셋",
            "🚪  종료 (자동 저장)"
        )
        AlertDialog.Builder(this)
            .setTitle(info.title)
            .setItems(items) { _, which ->
                when (which) {
                    1 -> pickSlot(true)
                    2 -> pickSlot(false)
                    3 -> toast(if (saveSram()) "게임 데이터 저장 완료" else "저장할 데이터가 없습니다")
                    4 -> reloadSram()
                    5 -> showCheats()
                    6 -> { fast = !fast; retroView.frameSpeed = if (fast) 2 else 1 }
                    7 -> { muted = !muted; retroView.audioEnabled = !muted }
                    8 -> confirm("정말 리셋할까요?") { retroView.reset() }
                    9 -> finish()
                }
            }
            .show()
    }

    private fun pickSlot(save: Boolean) {
        val labels = Array(4) { slotLabel("슬롯", it + 1) }
        AlertDialog.Builder(this)
            .setTitle(if (save) "진행 상황 저장" else "진행 상황 불러오기")
            .setItems(labels) { _, i ->
                val slot = i + 1
                if (save) {
                    toast(if (saveState(slot)) "슬롯 $slot 저장 완료" else "저장 실패")
                } else {
                    toast(if (loadState(slot)) "슬롯 $slot 불러옴" else "슬롯 $slot 에 저장된 상태가 없습니다")
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun reloadSram() {
        val f = store.sramFile(info.key)
        if (!f.isFile) { toast("저장된 게임 데이터가 없습니다"); return }
        confirm("마지막으로 저장한 게임 데이터로 되돌릴까요?\n(리셋 후 다시 불러오는 것을 권장)") {
            try { retroView.unserializeSRAM(f.readBytes()); toast("불러오기 완료") } catch (_: Throwable) { toast("실패") }
        }
    }

    // ---------------------------------------------------------------- 치트

    private fun showCheats() {
        val g = cheatGame
        if (g == null) {
            val msg = if (!store.cheatDbFile.isFile) "usercheat.dat 이 없습니다.\n첫 화면에서 '치트 DB 불러오기'로 가져오세요."
            else "usercheat.dat 에서 이 게임(${info.gameCode})의 치트를 찾지 못했습니다."
            AlertDialog.Builder(this).setTitle("치트").setMessage(msg).setPositiveButton("확인", null).show()
            return
        }
        val labels = Array(g.items.size) { g.items[it].label }
        val checked = BooleanArray(g.items.size) { enabledCheats.contains(it) }
        AlertDialog.Builder(this)
            .setTitle("치트 · ${g.title}")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("적용") { _, _ ->
                enabledCheats.clear()
                checked.forEachIndexed { i, b -> if (b) enabledCheats.add(i) }
                store.cheatSelFile(info.key).writeText(enabledCheats.joinToString(","))
                applyCheats()
                toast("치트 ${enabledCheats.size}개 적용")
            }
            .setNeutralButton("모두 해제") { _, _ ->
                enabledCheats.clear()
                store.cheatSelFile(info.key).writeText("")
                applyCheats()
                toast("치트 해제")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 이 코어는 개별 끄기를 지원하지 않으므로: 목록 초기화 후 켜진 것만 다시 등록 */
    private fun applyCheats() {
        val g = cheatGame ?: return
        val codes = enabledCheats.mapNotNull { g.items.getOrNull(it)?.code }
        retroView.queueEvent {
            try {
                LibretroDroid.resetCheat()
                codes.forEachIndexed { idx, code -> retroView.setCheat(idx, true, code, false) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("예") { _, _ -> ok() }
            .setNegativeButton("아니오", null).show()
    }
}
