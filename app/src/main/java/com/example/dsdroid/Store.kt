package com.example.dsdroid

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 앱 내부 저장소 구조
 *  roms/*.nds                    (백업 대상 아님 - 롬은 직접 복사)
 *  gamedata/<게임키>/battery.sav  게임 데이터(SRAM, 인게임 저장)
 *  gamedata/<게임키>/state_N.bin  진행 상황(세이브 스테이트) N=1..4, state_auto.bin = 자동 이어하기
 *  gamedata/<게임키>/cheats.txt   선택한 치트 목록
 *  usercheat.dat                  치트 DB
 * 게임키 = 게임코드 + 롬헤더 CRC → 폰이 달라도 같은 게임이면 같은 키
 */
class Store(private val ctx: Context) {
    val root: File = ctx.filesDir
    val romsDir = File(root, "roms").apply { mkdirs() }
    val dataRoot = File(root, "gamedata").apply { mkdirs() }
    val systemDir = File(root, "system").apply { mkdirs() }
    private val coreSaveRoot = File(root, "coresaves").apply { mkdirs() }
    val cheatDbFile = File(root, "usercheat.dat")
    val prefs = ctx.getSharedPreferences("dsdroid", Context.MODE_PRIVATE)

    var autoResume: Boolean
        get() = prefs.getBoolean("autoResume", true)
        set(v) = prefs.edit().putBoolean("autoResume", v).apply()

    fun gameDir(key: String) = File(dataRoot, key).apply { mkdirs() }
    fun coreSaveDir(key: String) = File(coreSaveRoot, key).apply { mkdirs() }
    fun sramFile(key: String) = File(gameDir(key), "battery.sav")
    fun stateFile(key: String, slot: Int) = File(gameDir(key), if (slot == 0) "state_auto.bin" else "state_$slot.bin")
    fun cheatSelFile(key: String) = File(gameDir(key), "cheats.txt")
    private fun metaFile(key: String) = File(gameDir(key), "meta.txt")

    data class RomEntry(val file: File, val info: RomInfo)

    fun listRoms(): List<RomEntry> =
        (romsDir.listFiles() ?: emptyArray())
            .mapNotNull { f -> NdsHeader.read(f)?.let { RomEntry(f, it) } }
            .sortedBy { it.info.title.lowercase(Locale.ROOT) }

    fun writeAtomic(dest: File, bytes: ByteArray) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    }

    private fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "game.nds"
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "game.nds"
    }

    /** @return 성공 시 RomEntry, 실패 시 null */
    fun importRom(uri: Uri): RomEntry? {
        val name = displayName(uri).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (!name.lowercase(Locale.ROOT).let { it.endsWith(".nds") || it.endsWith(".srl") }) return null
        val dest = File(romsDir, name)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out, 1 shl 16) }
        } ?: return null
        val info = NdsHeader.read(dest)
        if (info == null) { dest.delete(); return null }
        metaFile(info.key).writeText(info.title)
        return RomEntry(dest, info)
    }

    fun importCheatDb(uri: Uri): Boolean {
        val tmp = File(root, "usercheat.tmp")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out, 1 shl 16) }
        } ?: return false
        if (R4CheatDb.load(tmp) == null) { tmp.delete(); return false }
        if (cheatDbFile.exists()) cheatDbFile.delete()
        return tmp.renameTo(cheatDbFile)
    }

    fun deleteGameData(key: String) { File(dataRoot, key).deleteRecursively(); File(coreSaveRoot, key).deleteRecursively() }

    fun hasData(key: String): Boolean {
        val d = File(dataRoot, key)
        return d.isDirectory && (d.listFiles()?.any { it.name.endsWith(".sav") || it.name.startsWith("state_") } == true)
    }

    fun suggestedBackupName(): String =
        "DSDroid_backup_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".zip"

    /** 모든 게임 데이터(+usercheat.dat)를 zip 으로 백업. 반환: 파일 수 */
    fun backupTo(uri: Uri): Int {
        var count = 0
        val out = ctx.contentResolver.openOutputStream(uri) ?: return -1
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup_info.txt"))
            zip.write("DSDroid backup v1\n".toByteArray())
            zip.closeEntry()
            dataRoot.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
            if (cheatDbFile.isFile) {
                zip.putNextEntry(ZipEntry("usercheat.dat"))
                cheatDbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    /** 백업 zip 복원. 반환: 복원한 파일 수 (실패 -1) */
    fun restoreFrom(uri: Uri): Int {
        var count = 0
        val input = ctx.contentResolver.openInputStream(uri) ?: return -1
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = e.name.replace('\\', '/')
                val ok = !e.isDirectory && !name.contains("..") &&
                    (name.startsWith("gamedata/") || name == "usercheat.dat")
                if (ok) {
                    val target = File(root, name)
                    if (target.canonicalPath.startsWith(rootPath)) {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zip.copyTo(it) }
                        count++
                    }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return count
    }
}
