package com.example.dsdroid

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.zip.CRC32

data class RomInfo(val title: String, val gameCode: String, val crcStd: Long, val crcDb: Long, val key: String)

object NdsHeader {
    fun read(file: File): RomInfo? {
        if (!file.isFile || file.length() < 0x200) return null
        val buf = ByteArray(0x200)
        RandomAccessFile(file, "r").use { it.readFully(buf) }
        val title = String(buf, 0, 12, Charsets.US_ASCII).replace("\u0000", "").trim()
        val code = String(buf, 12, 4, Charsets.US_ASCII)
        val crc = CRC32().also { it.update(buf, 0, 0x200) }.value
        val dbCrc = crc.inv() and 0xFFFFFFFFL
        val safe = code.filter { it.isLetterOrDigit() }.ifEmpty { "UNKN" }
        val key = safe + "_" + String.format("%08X", crc)
        return RomInfo(title.ifEmpty { safe }, code, crc, dbCrc, key)
    }
}

data class CheatItem(val folder: String?, val name: String, val note: String, val code: String) {
    val label: String get() = if (folder.isNullOrEmpty()) name else "$folder · $name"
}

data class CheatGame(val title: String, val items: List<CheatItem>)

/** DeSmuME / R4 usercheat.dat 파서 (암호화·비암호화 모두 지원). */
object R4CheatDb {
    private const val HEADER = "R4 CheatCode"

    fun load(file: File): ByteArray? {
        if (!file.isFile || file.length() < 0x120) return null
        val data = file.readBytes()
        if (hasHeader(data)) return data
        val dec = data.copyOf()
        r4Decrypt(dec)
        return if (hasHeader(dec)) dec else null
    }

    private fun hasHeader(d: ByteArray): Boolean {
        if (d.size < HEADER.length) return false
        return String(d, 0, HEADER.length, Charsets.ISO_8859_1) == HEADER
    }

    private fun bit(v: Int, n: Int) = ((v ushr n) and 1) != 0

    /** DeSmuME CheatDBFile::R4Decrypt 포팅 (512바이트 블록, 블록 번호 n). */
    private fun r4Decrypt(buf: ByteArray) {
        var n = 0L
        var r = 0
        while (r < buf.size) {
            var key = ((n xor 0x484AL) and 0xFFFFL).toInt()
            val len = minOf(512, buf.size - r)
            for (i in 0 until len) {
                var xr = 0
                if ((key and 0x4000) != 0) xr = xr or 0x80
                if ((key and 0x1000) != 0) xr = xr or 0x40
                if ((key and 0x0800) != 0) xr = xr or 0x20
                if ((key and 0x0200) != 0) xr = xr or 0x10
                if ((key and 0x0080) != 0) xr = xr or 0x08
                if ((key and 0x0040) != 0) xr = xr or 0x04
                if ((key and 0x0002) != 0) xr = xr or 0x02
                if ((key and 0x0001) != 0) xr = xr or 0x01

                val k = (((buf[r + i].toInt() and 0xFF) shl 8) xor key) shl 16
                var x = k
                for (j in 1 until 32) x = x xor (k ushr j)
                key = 0
                if (bit(x, 23)) key = key or 0x8000
                if (bit(k, 22)) key = key or 0x4000
                if (bit(k, 21)) key = key or 0x2000
                if (bit(k, 20)) key = key or 0x1000
                if (bit(k, 19)) key = key or 0x0800
                if (bit(k, 18)) key = key or 0x0400
                if (bit(k, 17) != bit(x, 31)) key = key or 0x0200
                if (bit(k, 16) != bit(x, 30)) key = key or 0x0100
                if (bit(k, 30) != bit(k, 29)) key = key or 0x0080
                if (bit(k, 29) != bit(k, 28)) key = key or 0x0040
                if (bit(k, 28) != bit(k, 27)) key = key or 0x0020
                if (bit(k, 27) != bit(k, 26)) key = key or 0x0010
                if (bit(k, 26) != bit(k, 25)) key = key or 0x0008
                if (bit(k, 25) != bit(k, 24)) key = key or 0x0004
                if (bit(k, 25) != bit(x, 26)) key = key or 0x0002
                if (bit(k, 24) != bit(x, 25)) key = key or 0x0001
                buf[r + i] = (buf[r + i].toInt() xor xr).toByte()
            }
            r += 512
            n++
        }
    }

    private fun u32(d: ByteArray, o: Int): Long {
        if (o < 0 || o + 4 > d.size) throw IndexOutOfBoundsException()
        return (d[o].toLong() and 0xFF) or ((d[o + 1].toLong() and 0xFF) shl 8) or
            ((d[o + 2].toLong() and 0xFF) shl 16) or ((d[o + 3].toLong() and 0xFF) shl 24)
    }

    private fun u64(d: ByteArray, o: Int): Long = u32(d, o) or (u32(d, o + 4) shl 32)

    private fun cstrEnd(d: ByteArray, o: Int): Int {
        var i = o
        while (i < d.size && d[i].toInt() != 0) i++
        return i
    }

    private fun decode(d: ByteArray, from: Int, to: Int): String {
        val len = to - from
        if (len <= 0) return ""
        val utf8 = String(d, from, len, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return try { String(d, from, len, Charset.forName("x-windows-949")) } catch (e: Exception) { utf8 }
    }

    /** 검색 결과. exact=false 면 게임코드만 일치(헤더 CRC 불일치, 다른 버전일 수 있음). */
    class Lookup(
        val game: CheatGame?,
        val exact: Boolean,
        val dbGames: Int,
        val sameSerial: Int,
        val diag: String
    )

    private class IndexEntry(val serial: String, val crc: Long, val addr: Long)

    private fun readIndex(data: ByteArray): List<IndexEntry> {
        val list = ArrayList<IndexEntry>()
        var pos = 0x100
        try {
            while (pos + 16 <= data.size) {
                val addr = u64(data, pos + 8)
                if (addr == 0L) break
                val s = String(data, pos, 4, Charsets.ISO_8859_1)
                list.add(IndexEntry(s, u32(data, pos + 4), addr))
                pos += 16
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun countGames(data: ByteArray): Int = readIndex(data).size

    fun findGame(data: ByteArray, info: RomInfo): CheatGame? = lookup(data, info).game

    /** 게임코드 + 헤더 CRC 로 찾고, CRC 가 안 맞으면 같은 게임코드 항목으로 폴백한다. */
    fun lookup(data: ByteArray, info: RomInfo): Lookup {
        val index = readIndex(data)
        val serial = info.gameCode
        val sameSerial = index.filter { it.serial == serial || it.serial.reversed() == serial }
        val exact = sameSerial.filter { it.crc == info.crcDb || it.crc == info.crcStd }
        for (e in exact) {
            val g = parseGame(data, e.addr.toInt())
            if (g != null) return Lookup(g, true, index.size, sameSerial.size, "")
        }
        for (e in sameSerial) {
            if (exact.contains(e)) continue
            val g = parseGame(data, e.addr.toInt())
            if (g != null) return Lookup(g, false, index.size, sameSerial.size, "")
        }
        val diag = buildString {
            append("게임코드: ").append(serial).append('\n')
            append("헤더 CRC: ").append(String.format("%08X", info.crcStd))
            append(" / ").append(String.format("%08X", info.crcDb)).append('\n')
            append("DB 게임 수: ").append(index.size).append('\n')
            if (sameSerial.isEmpty()) append("DB 에 이 게임코드가 없습니다.")
            else append("게임코드는 있으나(").append(sameSerial.size).append("건) 치트 데이터를 해석하지 못했습니다.")
        }
        return Lookup(null, false, index.size, sameSerial.size, diag)
    }

    private class Folder(val name: String, var remaining: Int)

    private class Parsed(val items: List<CheatItem>, val clean: Boolean)

    /** 문자열 끝(NUL 다음)을 4바이트 경계로. p 는 NUL 의 위치. 절대 오프셋 기준. */
    private fun alignAfterNul(p: Int) = (p + 4) and 3.inv()

    private fun parseGame(d: ByteArray, base: Int): CheatGame? {
        if (base < 0 || base >= d.size) return null
        val titleEnd = cstrEnd(d, base)
        val title = decode(d, base, titleEnd)
        // 제목 뒤 항목 수 위치 후보: 정상 정렬 / base 기준 정렬 / 예전(잘못된) 정렬
        val cands = linkedSetOf(
            alignAfterNul(titleEnd),
            base + (((titleEnd - base) + 4) and 3.inv()),
            align4(titleEnd + 4)
        )
        var best: List<CheatItem>? = null
        for (c in cands) {
            val p = parseItems(d, c) ?: continue
            if (p.items.isEmpty()) continue
            if (p.clean) return CheatGame(title, p.items)
            if (best == null || p.items.size > best.size) best = p.items
        }
        return best?.let { CheatGame(title, it) }
    }

    private fun parseItems(d: ByteArray, countOff: Int): Parsed? {
        val count = try { u32(d, countOff) } catch (_: Exception) { return null }
        if (count < 1 || count > 20000) return null
        var cmd = countOff + 36 // 항목 수(4) + 마스터코드(32)
        val items = ArrayList<CheatItem>()
        val stack = ArrayList<Folder>()
        var i = 0L
        var clean = true
        try {
            while (i < 50000) {
                if (i >= count && stack.none { it.remaining > 0 }) break
                val v = u32(d, cmd)
                val kind = v and 0xF0000000L
                if (kind != 0L && kind != 0x10000000L) { clean = false; break }
                val nameOff = cmd + 4
                val nameEnd = cstrEnd(d, nameOff)
                if (nameEnd >= d.size) { clean = false; break }
                val noteOff = nameEnd + 1
                val noteEnd = cstrEnd(d, noteOff)
                if (noteEnd >= d.size) { clean = false; break }
                val name = decode(d, nameOff, nameEnd)
                val note = decode(d, noteOff, noteEnd)
                if (stack.isNotEmpty()) stack.last().remaining--
                val next = alignAfterNul(noteEnd)
                if (kind == 0x10000000L) {
                    stack.add(Folder(name, (v and 0xFFFFFFL).toInt()))
                    cmd = next
                } else {
                    val dwords = u32(d, next).toInt()
                    if (dwords < 0 || dwords > 0x1000 || next + 4 + dwords * 4 > d.size) { clean = false; break }
                    if (dwords >= 2) {
                        val sb = StringBuilder()
                        var t = 0
                        while (t + 1 < dwords) {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(String.format("%08X %08X", u32(d, next + 4 + t * 4), u32(d, next + 8 + t * 4)))
                            t += 2
                        }
                        items.add(CheatItem(stack.lastOrNull()?.name, name, note, sb.toString()))
                    }
                    cmd = next + 4 + dwords * 4
                }
                while (stack.isNotEmpty() && stack.last().remaining <= 0) stack.removeAt(stack.size - 1)
                i++
            }
        } catch (_: Exception) {
            clean = false
        }
        return Parsed(items, clean)
    }

    private fun align4(v: Int) = (v + 3) and 3.inv()
}
