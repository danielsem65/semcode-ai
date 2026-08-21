package com.danielsem65.semcodeai.fs

import kotlin.math.min

/** Minimal unified-style diff renderer (prefix/suffix trim around change hunk). */
object DiffUtil {

    fun unified(old: String, new: String, maxLines: Int = 60): String {
        val a = old.replace("\r\n", "\n").lines()
        val b = new.replace("\r\n", "\n").lines()

        if (a == b) return "(no textual changes)"

        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++

        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++

        val ctx = 3
        val out = StringBuilder()
        var total = 0
        var truncated = false

        fun line(text: String) {
            if (total >= maxLines) { truncated = true; return }
            out.appendLine(text)
            total++
        }

        val headStart = (p - ctx).coerceAtLeast(0)
        if (headStart > 0) line("⋯ $headStart unchanged lines")

        for (i in headStart until p) line("  " + a[i])

        val endA = a.size - s
        val endB = b.size - s
        for (i in p until endA) line("- " + a[i])
        for (i in p until endB) line("+ " + b[i])

        val tailShown = min(ctx, s)
        for (i in endA until endA + tailShown) line("  " + a[i])
        val hiddenTail = s - tailShown
        if (hiddenTail > 0) line("⋯ $hiddenTail more unchanged lines")

        if (truncated) line("⋯ diff truncated")

        return out.toString().trimEnd('\n')
    }
}
