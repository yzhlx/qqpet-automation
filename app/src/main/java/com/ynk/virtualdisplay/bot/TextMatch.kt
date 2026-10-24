package com.ynk.virtualdisplay.bot

/**
 * Whether an OCR line names the wanted text. Chinese OCR on game fonts swaps single characters
 * (measured: 世界地理课 read as 世界地理谋), so names of four or more characters tolerate one
 * substituted character; shorter labels such as 出门 must match exactly.
 */
object TextMatch {
    fun matches(seen: String, wanted: String): Boolean {
        val s = seen.trim().filterNot { it.isWhitespace() || it == '|' }
        if (s == wanted) return true
        if (wanted.length < 4 || s.length != wanted.length) return false
        return s.indices.count { s[it] != wanted[it] } <= 1
    }
}
