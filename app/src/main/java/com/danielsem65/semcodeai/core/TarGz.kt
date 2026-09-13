package com.danielsem65.semcodeai.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Minimal pure-Kotlin tar.gz extractor used for the OpenCode CLI bundle.
 *
 * Device toybox tar builds differ across OEMs and have been observed silently
 * skipping entries, so extraction never relies on the system tar. This handles
 * regular files, directories and symlinks; anything else is drained and skipped.
 * A separate small extractor (not a LinuxEnv change) keeps the proven path used
 * for distro rootfs installs completely untouched.
 */
object TarGz {

    fun extract(gz: File, dst: File) {
        FileInputStream(gz).use { fin ->
            val magic = ByteArray(2)
            if (readFully(fin, magic) != 2 || magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) {
                throw RuntimeException("downloaded archive is not gzip (truncated download?)")
            }
        }
        GZIPInputStream(FileInputStream(gz), 65536).use { gin ->
            parseTar(gin, dst)
        }
    }

    private fun readFully(ins: java.io.InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
        var total = 0
        while (total < len) {
            val n = ins.read(buf, off + total, len - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun parseTar(ins: java.io.InputStream, dst: File) {
        val header = ByteArray(512)
        var pendingLongName: String? = null

        while (true) {
            val got = readFully(ins, header)
            if (got < 512) break
            if (header.all { it == 0.toByte() }) break

            val name = tarString(header, 0, 100)
            val modeStr = tarString(header, 100, 8)
            val size = tarSize(header, 124, 12)
            val type = header[156]
            val linkName = tarString(header, 157, 100)
            val prefix = tarString(header, 345, 155)

            var entryName = pendingLongName ?: (if (prefix.isNotBlank()) "$prefix/$name" else name)
            pendingLongName = null
            if (entryName.isBlank()) { drainEntry(ins, size, null); continue }

            if (type == 'L'.code.toByte()) { // GNU long name
                val buf = ByteArray(size.toInt())
                readFully(ins, buf)
                pendingLongName = String(buf, 0, buf.size).trimEnd('\u0000')
                skipPadding(ins, size)
                continue
            }
            if (type == 'x'.code.toByte() || type == 'g'.code.toByte()) { // PAX ext header
                val buf = ByteArray(size.toInt())
                readFully(ins, buf)
                val pax = String(buf, 0, buf.size)
                Regex("path=([^\\n]+)").find(pax)?.let { pendingLongName = it.groupValues[1].trimEnd('\u0000') }
                skipPadding(ins, size)
                continue
            }

            val rel = entryName.removePrefix("./").trimStart('/')
            if (rel.isBlank()) { drainEntry(ins, size, null); continue }
            val safeRel = rel.split('/').filter { it != ".." }.joinToString("/")
            val outFile = File(dst, safeRel)

            when (type) {
                '5'.code.toByte() -> {
                    drainEntry(ins, size, null)
                    outFile.mkdirs()
                }
                '2'.code.toByte() -> {
                    drainEntry(ins, size, null)
                    outFile.parentFile?.mkdirs()
                    outFile.delete()
                    createSymlink(dst, outFile, linkName)
                }
                '1'.code.toByte() -> { // hardlink → copy
                    drainEntry(ins, size, null)
                    outFile.parentFile?.mkdirs()
                    val src = File(dst, linkName.removePrefix("./").trimStart('/'))
                    if (src.isFile) src.copyTo(outFile, overwrite = true)
                }
                '0'.code.toByte(), 0.toByte(), '7'.code.toByte() -> { // regular file
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { drainEntry(ins, size, it) }
                    if (modeStr.isNotBlank()) {
                        val m = modeStr.trim('\u0000', ' ').toIntOrNull(8) ?: 0
                        if (m and 0b001_001_001 != 0) outFile.setExecutable(true, false)
                    }
                }
                else -> drainEntry(ins, size, null)
            }
        }
    }

    private fun createSymlink(base: File, link: File, rawTarget: String) {
        val target = rawTarget.trim()
        try {
            Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get(target))
            return
        } catch (_: Exception) {
        }
        val resolved = if (target.startsWith("/")) File(base, target.trimStart('/'))
        else File(link.parentFile, target)
        if (resolved.isDirectory) {
            resolved.copyRecursively(link, overwrite = true)
        } else if (resolved.isFile) {
            resolved.copyTo(link, overwrite = true)
            link.setExecutable(resolved.canExecute(), false)
        }
    }

    private fun drainEntry(ins: java.io.InputStream, size: Long, out: FileOutputStream?) {
        val buf = ByteArray(65536)
        var remaining = size
        while (remaining > 0) {
            val n = ins.read(buf, 0, if (remaining < buf.size) remaining.toInt() else buf.size)
            if (n <= 0) throw RuntimeException("unexpected end of archive")
            out?.write(buf, 0, n)
            remaining -= n
        }
        skipPadding(ins, size)
    }

    private fun skipPadding(ins: java.io.InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) {
            val junk = ByteArray(pad)
            readFully(ins, junk)
        }
    }

    private fun tarString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && h[end] != 0.toByte()) end++
        return String(h, off, end - off)
    }

    private fun tarSize(h: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        var started = false
        for (i in off until off + len) {
            val c = h[i].toInt() and 0xFF
            if (c == 0 || c == ' '.code) {
                if (started) break else continue
            }
            started = true
            v = v * 8 + (c - '0'.code)
        }
        return v
    }
}