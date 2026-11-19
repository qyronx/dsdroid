package com.example.dsdroid

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var listView: ListView
    private lateinit var empty: TextView
    private lateinit var cheatStatus: TextView
    private var roms: List<Store.RomEntry> = emptyList()
    private var busy: AlertDialog? = null

    private val pickRoms = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        runBusy("롬 가져오는 중…") {
            var ok = 0; var fail = 0
            for (u in uris) { if (store.importRom(u) != null) ok++ else fail++ }
            "추가 ${ok}개" + if (fail > 0) " / 실패 ${fail}개 (.nds 만 지원)" else ""
        }
    }
    private val pickCheat = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runBusy("치트 DB 확인 중…") {
            if (store.importCheatDb(uri)) "usercheat.dat 적용 완료" else "올바른 usercheat.dat(R4 형식)이 아닙니다"
        }
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        runBusy("백업 만드는 중…") {
            val n = store.backupTo(uri)
            if (n >= 0) "백업 완료 (파일 ${n}개)" else "백업 실패"
        }
    }
    private val pickRestore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runBusy("복원 중…") {
            val n = try { store.restoreFrom(uri) } catch (e: Exception) { -1 }
            if (n > 0) "복원 완료 (파일 ${n}개)\n같은 게임 롬을 추가하면 이어서 플레이할 수 있어요" else "복원할 데이터가 없거나 올바른 백업이 아닙니다"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun btn(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(14), dp(8), dp(14))
        background = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(color) }
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, view ->
            addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
    }

    private fun buildUi() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            setBackgroundColor(0xFF101018.toInt())
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(16) + i.left, dp(16) + i.top, dp(16) + i.right, dp(8) + i.bottom)
            insets
        }
        rootLayout.addView(TextView(this).apply {
            text = "DSDroid"; textSize = 28f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        rootLayout.addView(TextView(this).apply {
            text = "내 롬 파일(.nds)을 추가해서 플레이하세요"; textSize = 13f; setTextColor(0xFF9A9CC0.toInt())
            setPadding(0, 0, 0, dp(12))
        })

        rootLayout.addView(row(
            btn("＋ 롬 추가", 0xFF4361EE.toInt()) { pickRoms.launch(arrayOf("*/*")) },
            btn("🧪 치트 DB(usercheat.dat)", 0xFF7B2CBF.toInt()) { pickCheat.launch(arrayOf("*/*")) }
        ))
        rootLayout.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        rootLayout.addView(row(
            btn("📤 백업 만들기", 0xFF2A9D8F.toInt()) { createBackup.launch(store.suggestedBackupName()) },
            btn("📥 백업 복원", 0xFFE76F51.toInt()) { pickRestore.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        ))

        cheatStatus = TextView(this).apply { textSize = 12f; setTextColor(0xFF9A9CC0.toInt()); setPadding(0, dp(10), 0, dp(6)) }
        rootLayout.addView(cheatStatus)

        listView = ListView(this).apply {
            divider = null
            dividerHeight = dp(8)
            adapter = RomAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                startActivity(Intent(this@MainActivity, GameActivity::class.java)
                    .putExtra(GameActivity.EXTRA_ROM, roms[pos].file.absolutePath))
            }
            setOnItemLongClickListener { _, _, pos, _ -> showRomMenu(roms[pos]); true }
        }
        empty = TextView(this).apply {
            text = "아직 롬이 없어요.\n위의 '＋ 롬 추가'를 눌러 .nds 파일을 선택하세요."
            setTextColor(0xFF9A9CC0.toInt()); gravity = Gravity.CENTER; textSize = 14f
        }
        rootLayout.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootLayout.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(rootLayout)
    }

    private fun refresh() {
        roms = store.listRoms()
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        listView.visibility = if (roms.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (roms.isEmpty()) View.VISIBLE else View.GONE
        cheatStatus.text = if (store.cheatDbFile.isFile)
            "치트 DB: usercheat.dat 적용됨 (${store.cheatDbFile.length() / 1024} KB)"
        else "치트 DB: 없음 — usercheat.dat 을 불러오면 게임 중 치트 메뉴에서 사용할 수 있어요"
    }

    private fun showRomMenu(r: Store.RomEntry) {
        AlertDialog.Builder(this).setTitle(r.info.title)
            .setItems(arrayOf("롬 목록에서 삭제 (저장 데이터는 유지)", "이 게임의 저장 데이터 삭제")) { _, i ->
                when (i) {
                    0 -> { r.file.delete(); refresh() }
                    1 -> AlertDialog.Builder(this).setMessage("이 게임의 게임 데이터/진행 상황/치트 선택을 모두 삭제합니다.")
                        .setPositiveButton("삭제") { _, _ -> store.deleteGameData(r.info.key); refresh() }
                        .setNegativeButton("취소", null).show()
                }
            }.show()
    }

    private fun runBusy(msg: String, work: () -> String) {
        busy = AlertDialog.Builder(this).setMessage(msg).setCancelable(false).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { work() } catch (e: Exception) { "오류: ${e.message}" }
            }
            busy?.dismiss()
            AlertDialog.Builder(this@MainActivity).setMessage(result).setPositiveButton("확인", null).show()
            refresh()
        }
    }

    private inner class RomAdapter : BaseAdapter() {
        override fun getCount() = roms.size
        override fun getItem(position: Int) = roms[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = roms[position]
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE); textSize = 16f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = AbsListViewLayoutParams()
            }
            val saved = store.hasData(r.info.key)
            val sub = r.info.gameCode + (if (saved) "  ·  💾 저장 데이터 있음" else "")
            tv.text = android.text.SpannableStringBuilder().apply {
                append(r.info.title)
                append("\n")
                val s = length
                append(sub)
                setSpan(android.text.style.ForegroundColorSpan(0xFF9A9CC0.toInt()), s, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.75f), s, length, 0)
            }
            tv.background = GradientDrawable().apply { cornerRadius = dpf(12f); setColor(0xFF1B1C2E.toInt()) }
            return tv
        }

        @Suppress("FunctionName")
        private fun AbsListViewLayoutParams() =
            android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
