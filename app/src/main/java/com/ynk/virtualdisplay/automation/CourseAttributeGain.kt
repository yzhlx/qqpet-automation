package com.ynk.virtualdisplay.automation

/**
 * STR-09：课程页上写明的属性收益（例如「力量+2」）。
 *
 * 这是页面上写着的奖励说明，不是宠物当前的属性数值——房间页不公开属性栏，
 * 读不到属性总量时只能如实记「这门课每节写明加多少」。
 *
 * 读不到就是 null（未知），绝不按课程名猜一个数值出来。
 */
data class CourseAttributeGain(val attribute: String, val amount: Int) {
    companion object {
        // ML Kit 在这一页会把汉字拆开（"力 量 + 2"），所以关键字之间允许出现空格。
        private val gain = Regex("(力\\s*量|智\\s*力|魅\\s*力)\\s*[＋+]\\s*([0-9]+)")

        fun parse(text: String): CourseAttributeGain? {
            val match = gain.find(text) ?: return null
            val attribute = match.groupValues[1].replace(Regex("\\s+"), "")
            val amount = match.groupValues[2].toIntOrNull() ?: return null
            if (amount <= 0) return null
            return CourseAttributeGain(attribute, amount)
        }
    }
}
