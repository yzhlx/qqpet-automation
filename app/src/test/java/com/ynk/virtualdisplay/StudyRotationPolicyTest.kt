package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CourseCostReading
import com.ynk.virtualdisplay.automation.StudyRotation
import com.ynk.virtualdisplay.automation.StudyRotationPolicy
import com.ynk.virtualdisplay.automation.StudyStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** STR-09：均衡属性目标的排课与花费计算（纯函数）。 */
class StudyRotationPolicyTest {

    private val rotation = listOf(
        StudyRotation("力量", "study:蹦蹦跳跳体能课"),
        StudyRotation("智力", "study:看图识世界课"),
        StudyRotation("魅力", "study:闪亮自我表达课"))

    @Test fun theRotationRunsInOrderAndComesBackAround() {
        val step = StudyStep("s", rotation.first().courseId, rounds = 4, rotation = rotation)
        val schedule = StudyRotationPolicy.schedule(step, 4)
        assertEquals(listOf("力量", "智力", "魅力", "力量"), schedule.map { it.attribute })
        assertEquals(listOf("study:蹦蹦跳跳体能课", "study:看图识世界课", "study:闪亮自我表达课",
            "study:蹦蹦跳跳体能课"), schedule.map { it.courseId })
    }

    @Test fun aPlanWithoutRotationKeepsItsOwnCourseAndAttribute() {
        val step = StudyStep("s", "study:蹦蹦跳跳体能课", rounds = 2, targetAttribute = "力量")
        val schedule = StudyRotationPolicy.schedule(step, 2)
        assertEquals(listOf("力量", "力量"), schedule.map { it.attribute })
        assertEquals(listOf("study:蹦蹦跳跳体能课", "study:蹦蹦跳跳体能课"), schedule.map { it.courseId })
    }

    @Test fun theAttributeComesFromTheCourseWhenTheUserNeverPickedOne() {
        val step = StudyStep("s", "study:看图识世界课", rounds = 1)
        assertEquals("智力", StudyRotationPolicy.schedule(step, 1).single().attribute)
    }

    @Test fun tuitionIsSummedInTheRealOrderNotTheMostExpensiveCourseTimesRounds() {
        val step = StudyStep("s", rotation.first().courseId, rounds = 3, rotation = rotation)
        val lessons = StudyRotationPolicy.schedule(step, 3)
        val costs = mapOf(
            "study:蹦蹦跳跳体能课" to CourseCostReading(15, 600),
            "study:看图识世界课" to CourseCostReading(20, 600),
            "study:闪亮自我表达课" to CourseCostReading(25, 600))
        assertEquals(60, StudyRotationPolicy.tuitionTotal(lessons, costs))
        assertEquals(25, StudyRotationPolicy.maxTuition(lessons, costs))
    }

    @Test fun minutesUseEachCourseItsOwnLength() {
        val step = StudyStep("s", rotation.first().courseId, rounds = 3, rotation = rotation)
        val lessons = StudyRotationPolicy.schedule(step, 3)
        val minutes = mapOf(
            "study:蹦蹦跳跳体能课" to 10,
            "study:看图识世界课" to 30,
            "study:闪亮自我表达课" to 30)
        assertEquals(70, StudyRotationPolicy.minutesTotal(lessons, minutes))
    }

    @Test fun anUnreadCostIsUnknownAndNeverZero() {
        val step = StudyStep("s", rotation.first().courseId, rounds = 2, rotation = rotation)
        val lessons = StudyRotationPolicy.schedule(step, 2)
        try {
            StudyRotationPolicy.tuitionTotal(lessons, mapOf("study:蹦蹦跳跳体能课" to CourseCostReading(15, 600)))
            fail("Expected an unread cost to stop the plan")
        } catch (_: IllegalStateException) { }
    }

    @Test fun noLessonMeansNoCost() {
        val step = StudyStep("s", rotation.first().courseId, rounds = 3, rotation = rotation)
        assertTrue(StudyRotationPolicy.schedule(step, 0).isEmpty())
    }

    @Test fun aPlanWithoutACatalogAttributeKeepsItUnknown() {
        val step = StudyStep("s", "study:未命名课程", rounds = 1)
        assertNull(StudyRotationPolicy.schedule(step, 1).single().attribute)
    }
}
