package com.ynk.virtualdisplay.automation

/**
 * 学习目标属性（CORE-04、CORE-05）：
 * 允许用户选择力量、智力、魅力作为养宠培养方向，系统自动匹配并切换至对应的初级学园课程。
 */
enum class StudyAttribute(
    val label: String,
    val primaryCourseName: String,
    val primaryCourseId: String,
    val candidateCourses: List<String>,
) {
    STRENGTH(
        label = "力量",
        primaryCourseName = "蹦蹦跳跳体能课",
        primaryCourseId = "study:蹦蹦跳跳体能课",
        candidateCourses = listOf("蹦蹦跳跳体能课", "田径运动课"),
    ),
    INTELLECT(
        label = "智力",
        primaryCourseName = "看图识世界课",
        primaryCourseId = "study:看图识世界课",
        candidateCourses = listOf("看图识世界课", "世界地理课"),
    ),
    CHARM(
        label = "魅力",
        primaryCourseName = "闪亮自我表达课",
        primaryCourseId = "study:闪亮自我表达课",
        candidateCourses = listOf("闪亮自我表达课", "演说表达课"),
    );

    companion object {
        fun fromLabel(label: String?): StudyAttribute? =
            entries.firstOrNull { it.label == label || it.name.equals(label, ignoreCase = true) }

        fun forCourseId(courseId: String): StudyAttribute? {
            val name = courseId.substringAfter(':', courseId)
            return entries.firstOrNull { attr ->
                attr.candidateCourses.any { it == name }
            }
        }

        /**
         * 根据用户选择的目标属性和已扫描的课程表，选出最合适的已解锁课程。
         * 如果尚无扫描数据，则回退到该属性的默认初级课程。
         */
        fun resolveCourse(attribute: StudyAttribute, catalog: CourseCatalog?): CourseEntry {
            if (catalog != null) {
                val unlocked = catalog.unlocked()
                // 优先选择该属性下已解锁的候选课程
                val matched = unlocked.filter { entry ->
                    attribute.candidateCourses.any { it == entry.name }
                }
                if (matched.isNotEmpty()) {
                    return matched.first()
                }
            }
            return CourseEntry(
                id = attribute.primaryCourseId,
                name = attribute.primaryCourseName,
                durationMinutes = 10,
                unlocked = true,
                rowTop = 0,
                rowBottom = 0,
            )
        }
    }
}
