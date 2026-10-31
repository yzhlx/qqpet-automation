package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CourseNames
import org.junit.Assert.assertEquals
import org.junit.Test

class CourseNamesTest {
    @Test fun measuredSlipsFoldToTheMeasuredCourse() {
        assertEquals("世界地理课", CourseNames.canonical("世界地理谍"))
        assertEquals("世界地理课", CourseNames.canonical("世界地理谋"))
        assertEquals("看图识世界课", CourseNames.canonical("香图识世界谍"))
        assertEquals("演说表达课", CourseNames.canonical("演濱说衷达谋"))
        assertEquals("蹦蹦跳跳体能课", CourseNames.canonical("|蹦蹦跳跳体能课"))
    }

    @Test fun textFarFromEveryMeasuredCourseStaysAsRead() {
        assertEquals("高级烹饪课", CourseNames.canonical("高级烹饪课"))
        assertEquals("世界地", CourseNames.canonical("世界地"))
    }
}
