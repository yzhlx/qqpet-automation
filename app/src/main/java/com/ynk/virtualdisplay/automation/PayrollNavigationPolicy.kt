package com.ynk.virtualdisplay.automation

/** A payroll back action is distinct from starting another shift. No fixed tap fallback. */
object PayrollNavigationPolicy {
    data class Proof(val payroll: Boolean, val option: String?, val minutes: Int?, val award: Int?)
    data class Target(val x: Float, val y: Float)
    enum class Page { OUTDOOR, ROOM, WORK_DETAIL, UNKNOWN }
    data class ExitResult(val page: Page, val reason: String) {
        val homeReached: Boolean get() = page == Page.OUTDOOR || page == Page.ROOM
    }

    fun returnTarget(proof: Proof, expected: Proof?, width: Int, height: Int, pixel: (Int, Int) -> Int): Target? {
        if (!proof.payroll || expected?.payroll != true || expected.option.isNullOrBlank() ||
            expected.minutes == null || expected.minutes <= 0 || proof.option != expected.option ||
            proof.minutes != expected.minutes || proof.award == null || proof.award <= 0 ||
            (expected.award != null && expected.award != proof.award)) return null
        if (width !in 200..4096 || height !in 400..8192) return null

        // Search a bounded top-left navigation region, not a remembered click coordinate.
        // A target exists only if this very frame contains one connected left-chevron shape.
        val left = width * 2 / 100
        val right = width * 13 / 100
        val top = height * 6 / 100
        val bottom = height * 14 / 100
        val rw = right - left
        val rh = bottom - top
        val dark = BooleanArray(rw * rh) { index ->
            val color = pixel(left + index % rw, top + index / rw)
            val r = color ushr 16 and 255
            val g = color ushr 8 and 255
            val b = color and 255
            (color ushr 24) >= 200 && maxOf(r, g, b) < 125 && maxOf(r, g, b) - minOf(r, g, b) < 35
        }
        val queue = IntArray(dark.size)
        val candidates = mutableListOf<Target>()
        for (seed in dark.indices) {
            if (!dark[seed]) continue
            var head = 0
            var tail = 1
            queue[0] = seed
            dark[seed] = false
            var minX = rw
            var maxX = 0
            var minY = rh
            var maxY = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % rw
                val y = p / rw
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until rw || ny !in 0 until rh) continue
                    val n = ny * rw + nx
                    if (dark[n]) { dark[n] = false; queue[tail++] = n }
                }
            }
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            val density = tail.toDouble() / (w * h)
            if (w < width * 0.012 || w > width * 0.07 || h < height * 0.012 || h > height * 0.055 ||
                h.toDouble() / w !in 1.2..3.2 || density !in 0.08..0.45) continue
            val upper = mutableListOf<Int>()
            val middle = mutableListOf<Int>()
            val lower = mutableListOf<Int>()
            for (i in 0 until tail) {
                val x = queue[i] % rw
                val y = queue[i] / rw
                if (y <= minY + h * 0.22) upper.add(x)
                if (kotlin.math.abs(y - (minY + maxY) / 2.0) <= h * 0.10) middle.add(x)
                if (y >= maxY - h * 0.22) lower.add(x)
            }
            if (upper.isEmpty() || middle.isEmpty() || lower.isEmpty()) continue
            val u = upper.average(); val m = middle.average(); val l = lower.average()
            if (u - m < w * 0.30 || l - m < w * 0.30 || kotlin.math.abs(u - l) > w * 0.30) continue
            candidates.add(Target(left + (minX + maxX) / 2f, top + (minY + maxY) / 2f))
        }
        return candidates.singleOrNull()
    }

    fun exitPage(outdoor: Boolean, room: Boolean, workDetail: Boolean, payroll: Boolean): Page {
        if (payroll || listOf(outdoor, room, workDetail).count { it } != 1) return Page.UNKNOWN
        return when { outdoor -> Page.OUTDOOR; room -> Page.ROOM; workDetail -> Page.WORK_DETAIL; else -> Page.UNKNOWN }
    }
}
