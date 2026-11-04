package com.ynk.virtualdisplay.automation

/**
 * STR-09「均衡属性目标」：排出本次要上的课，并按每一节自己那门课算学费和时长（纯函数，JVM 可测）。
 *
 * 均衡不是「一节课同时练三项」，而是按固定顺序轮换：第 1 节力量、第 2 节智力、第 3 节魅力、
 * 第 4 节回到力量。学费因此必须按真实顺序累加，不能拿最贵那门课乘节数。
 *
 * 没开均衡的方案走同一条路径：每一节都是同一门课，属性沿用方案自己的目标属性。
 * 读不到某门课的学费或时长就是未知，函数直接失败，不把未知当成 0。
 */
object StudyRotationPolicy {

    /** 一节课：上哪门课、这门课练哪个属性。 */
    data class Lesson(
        val courseId: String,
        val courseName: String,
        val attribute: String?,
    )

    /**
     * 排出本次要上的课。
     *
     * @param step 方案里的学习步骤
     * @param rounds 本次实际要上的节数（受今日停止线约束）
     */
    fun schedule(step: StudyStep, rounds: Int): List<Lesson> {
        require(rounds >= 0) { "STUDY_ROTATION_ROUNDS_INVALID:$rounds" }
        if (rounds == 0) return emptyList()
        if (step.rotation.isEmpty()) {
            val attribute = step.targetAttribute ?: StudyAttribute.forCourseId(step.courseId)?.label
            return List(rounds) { Lesson(step.courseId, courseNameOf(step.courseId), attribute) }
        }
        return List(rounds) { index ->
            val item = step.rotation[index % step.rotation.size]
            Lesson(item.courseId, courseNameOf(item.courseId), item.attribute)
        }
    }

    /** 真实顺序的学费合计。 */
    fun tuitionTotal(lessons: List<Lesson>, costs: Map<String, CourseCostReading>): Int =
        lessons.sumOf { lesson ->
            val cost = costs[lesson.courseId] ?: error("COURSE_COST_UNREADABLE:${lesson.courseId}")
            cost.tuitionCoins
        }

    /** 最贵那门课的学费：币值闸门按它检查余额够不够付下一节课。 */
    fun maxTuition(lessons: List<Lesson>, costs: Map<String, CourseCostReading>): Int =
        lessons.maxOfOrNull { lesson ->
            val cost = costs[lesson.courseId] ?: error("COURSE_COST_UNREADABLE:${lesson.courseId}")
            cost.tuitionCoins
        } ?: 0

    /** 每一节用它自己那门课的时长。 */
    fun minutesTotal(lessons: List<Lesson>, minutes: Map<String, Int>): Int =
        lessons.sumOf { lesson ->
            minutes[lesson.courseId] ?: error("COURSE_DURATION_MISSING:${lesson.courseId}")
        }

    private fun courseNameOf(courseId: String): String = courseId.substringAfter(':', courseId)
}
