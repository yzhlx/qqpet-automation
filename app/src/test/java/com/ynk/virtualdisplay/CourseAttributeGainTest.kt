package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CourseAttributeGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** STR-09：课程页上写明的属性收益。读不到就是未知，不按课程名反推。 */
class CourseAttributeGainTest {

    @Test fun theRewardRowIsRead() {
        val gain = CourseAttributeGain.parse(
            "蹦蹦跳跳体能课 消耗15(当前600) 体力5(当前13) 清洁2(当前11) 学分10 奖励 力量+2")
        assertNotNull(gain)
        assertEquals("力量", gain!!.attribute)
        assertEquals(2, gain.amount)
    }

    @Test fun theSpacedOutTextOfTheRealPageIsStillRead() {
        val gain = CourseAttributeGain.parse("闪 亮 自 我 表 达 课 奖 励 魅 力 + 5")
        assertNotNull(gain)
        assertEquals("魅力", gain!!.attribute)
        assertEquals(5, gain.amount)
    }

    @Test fun staminaIsNotAnAttribute() {
        assertNull(CourseAttributeGain.parse("提升体力和反应 消耗15(当前600) 体力5(当前13)"))
    }

    @Test fun anUnknownRewardStaysUnknown() {
        assertNull(CourseAttributeGain.parse("萌芽夏令营 消耗45(当前600) 奖励 随机属性+5"))
        assertNull(CourseAttributeGain.parse(""))
    }
}
