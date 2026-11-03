package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class StudyAttributeTest {

    @Test
    fun attributesCoverStrengthIntellectCharm() {
        val labels = StudyAttribute.entries.map { it.label }
        assertTrue(labels.contains("力量"))
        assertTrue(labels.contains("智力"))
        assertTrue(labels.contains("魅力"))
    }

    @Test
    fun forCourseIdMapsCoursesAccurately() {
        assertEquals(StudyAttribute.STRENGTH, StudyAttribute.forCourseId("study:蹦蹦跳跳体能课"))
        assertEquals(StudyAttribute.STRENGTH, StudyAttribute.forCourseId("study:田径运动课"))
        assertEquals(StudyAttribute.INTELLECT, StudyAttribute.forCourseId("study:看图识世界课"))
        assertEquals(StudyAttribute.INTELLECT, StudyAttribute.forCourseId("study:世界地理课"))
        assertEquals(StudyAttribute.CHARM, StudyAttribute.forCourseId("study:闪亮自我表达课"))
        assertEquals(StudyAttribute.CHARM, StudyAttribute.forCourseId("study:演说表达课"))
    }

    @Test
    fun resolveCourseFallsBackToPrimaryWithoutCatalog() {
        val course = StudyAttribute.resolveCourse(StudyAttribute.INTELLECT, null)
        assertEquals("study:看图识世界课", course.id)
        assertEquals("看图识世界课", course.name)
        assertTrue(course.unlocked)
    }

    @Test
    fun resolveCoursePicksScannedCandidateWhenAvailable() {
        val entries = listOf(
            CourseEntry("study:看图识世界课", "看图识世界课", 10, true, 100, 200),
            CourseEntry("study:世界地理课", "世界地理课", 10, true, 200, 300),
        )
        val catalog = CourseCatalog(scannedAtMs = System.currentTimeMillis(), courses = entries, complete = true)

        val course = StudyAttribute.resolveCourse(StudyAttribute.INTELLECT, catalog)
        assertEquals("study:看图识世界课", course.id)
    }

    @Test
    fun sessionPlanHoldsTargetAttribute() {
        val plan = SessionPlan(
            courseId = "study:看图识世界课",
            targetAttribute = "智力",
            rounds = 2,
        )
        assertEquals("智力", plan.targetAttribute)
        assertEquals("study:看图识世界课", plan.courseId)
        assertEquals(2, plan.rounds)
    }

    @Test
    fun planV2StudyStepSerializesAndRestoresTargetAttribute() {
        val step = StudyStep("plan:study", "study:闪亮自我表达课", 2, "魅力")
        val plan = PlanV2(
            planId = "test_plan",
            name = "培养方案",
            dailyBudgetMinutes = 120,
            steps = listOf(step),
        )
        val json = PlanV2JsonCodec.encode(plan)
        val restored = PlanV2JsonCodec.decode(json)

        val restoredStep = restored.steps.filterIsInstance<StudyStep>().single()
        assertEquals("魅力", restoredStep.targetAttribute)
        assertEquals("study:闪亮自我表达课", restoredStep.courseId)
        assertEquals(2, restoredStep.rounds)
    }
}
