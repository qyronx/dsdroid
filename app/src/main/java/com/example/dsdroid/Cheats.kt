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

    fun findGame(data: ByteArray, info: RomInfo): CheatGame? {
        val serial = info.gameCode
        var pos = 0x100
        try {
            while (pos + 24 <= data.size) {
                val s = String(data, pos, 4, Charsets.ISO_8859_1)
                val crc = u32(data, pos + 4)
                val addr = u64(data, pos + 8)
                if (addr == 0L) break
                if (s == serial && (crc == info.crcDb || crc == info.crcStd)) {
                    return parseGame(data, addr.toInt())
                }
                pos += 16
            }
        } catch (_: Exception) {
        }
        return null
    }

    private class Folder(val name: String, var remaining: Int)

    private fun align4(v: Int) = (v + 3) and 3.inv()

    private fun parseGame(d: ByteArray, base: Int): CheatGame? {
        val titleEnd = cstrEnd(d, base)
        val title = decode(d, base, titleEnd)
        val countOff = align4(titleEnd + 4)
        val count = u32(d, countOff).toInt()
        var cmd = countOff + 36
        val items = ArrayList<CheatItem>()
        val stack = ArrayList<Folder>()
        try {
            for (i in 0 until count) {
                val v = u32(d, cmd)
                val isFolder = (v and 0xF0000000L) == 0x10000000L
                val nameOff = cmd + 4
                val nameEnd = cstrEnd(d, nameOff)
                val name = decode(d, nameOff, nameEnd)
                val noteOff = nameEnd + 1
                val noteEnd = cstrEnd(d, noteOff)
                val note = decode(d, noteOff, noteEnd)
                if (stack.isNotEmpty()) stack.last().remaining--
                if (isFolder) {
                    stack.add(Folder(name, (v and 0xFFFFFFL).toInt()))
                    cmd = align4(noteEnd + 1)
                } else {
                    val lenOff = align4(noteEnd + 1)
                    val dwords = u32(d, lenOff).toInt()
                    if (dwords in 2..0x400) {
                        val sb = StringBuilder()
                        var t = 0
                        while (t + 1 < dwords) {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(String.format("%08X %08X", u32(d, lenOff + 4 + t * 4), u32(d, lenOff + 8 + t * 4)))
                            t += 2
                        }
                        val folder = stack.lastOrNull()?.name
                        items.add(CheatItem(folder, name, note, sb.toString()))
                    }
                    cmd += (((v and 0xFFFFFFL) + 1) * 4).toInt()
                }
                while (stack.isNotEmpty() && stack.last().remaining <= 0) stack.removeAt(stack.size - 1)
            }
        } catch (_: Exception) {
        }
        return if (items.isEmpty()) null else CheatGame(title, items)
    }
}
