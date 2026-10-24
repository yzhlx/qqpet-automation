package com.ynk.virtualdisplay.bot

/**
 * BotConfig as "key=value" lines, so it round-trips through an Intent extra and a settings file on
 * the plain JVM (org.json is a stub there). Unknown keys are ignored; missing keys take defaults.
 */
object BotConfigCodec {
    fun encode(c: BotConfig): String = listOf(
        "course" to (c.course ?: ""),
        "courseMinutes" to c.courseMinutes,
        "lessons" to c.lessons,
        "energyTarget" to c.energyTarget,
        "cleanTarget" to c.cleanTarget,
        "workWhenShort" to c.workWhenShort,
        "dailyMinutes" to c.dailyMinutes,
    ).joinToString("\n") { (k, v) -> "$k=$v" }

    fun decode(text: String): BotConfig {
        val d = BotConfig()
        val m = text.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
        fun int(key: String, default: Int) = m[key]?.toIntOrNull() ?: default
        return BotConfig(
            course = m["course"]?.takeIf { it.isNotEmpty() },
            courseMinutes = int("courseMinutes", d.courseMinutes),
            lessons = int("lessons", d.lessons),
            energyTarget = int("energyTarget", d.energyTarget),
            cleanTarget = int("cleanTarget", d.cleanTarget),
            workWhenShort = m["workWhenShort"]?.toBooleanStrictOrNull() ?: d.workWhenShort,
            dailyMinutes = int("dailyMinutes", d.dailyMinutes),
        )
    }
}
