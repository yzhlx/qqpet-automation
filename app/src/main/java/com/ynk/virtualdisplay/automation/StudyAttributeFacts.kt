package com.ynk.virtualdisplay.automation

/**
 * STR-09：本次会话里一个属性的学习事实。
 *
 * [rounds] 是这个属性实际上了几节（已确认完成才计入）。
 * [gain] 是这几节在课程页上写明的属性收益合计；[lessonsWithGain] 说明其中有几节读到了这个数字。
 * 读不到就是未知（gain 为 null），绝不用课程名反推一个数值。
 */
data class StudyAttributeFact(
    val attribute: String,
    val rounds: Int,
    val gain: Int?,
    val lessonsWithGain: Int,
)
