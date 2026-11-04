package com.ynk.virtualdisplay.automation

/**
 * STR-10：把课表里的候选课程和课程页读到的学费、时长、属性收益放在一起比较。
 *
 * 排名只使用完整且未过期的读数：课表里的时长 + 课程页读到的学费。
 * 学费或收益读不到时保持未知，不参与排名，也不会被猜成 0。
 * 结果只是方案页的提示，不会自动换课。
 */
object CourseComparison {
    data class Row(
        val courseId: String,
        val name: String,
        val durationMinutes: Int?,
        val tuitionCoins: Int?,
        val attributeGain: CourseAttributeGain?,
        val readingFresh: Boolean,
        val coinsPerMinute: Double?,
        val coinsPerAttribute: Double?,
    )

    data class Summary(
        val rows: List<Row>,
        val cheapestTuition: Row?,
        val cheapestPerMinute: Row?,
        val bestAttributeValue: Row?,
        val unknownNames: List<String>,
    ) {
        val hasAnyReading: Boolean get() = rows.any { it.tuitionCoins != null }
    }

    fun compare(courses: List<CourseEntry>, readings: Map<String, CourseReading>, nowMs: Long): Summary {
        val rows = courses.filter { it.unlocked }.map { course ->
            val reading = readings[course.id]
            val fresh = reading != null &&
                reading.readAtMs > 0 && nowMs >= reading.readAtMs &&
                nowMs - reading.readAtMs <= SessionPlan.CATALOG_MAX_AGE_MS &&
                reading.durationMinutes > 0 && reading.tuitionCoins > 0
            if (reading == null || !fresh) {
                Row(course.id, course.name, course.durationMinutes, null, null, false, null, null)
            } else {
                Row(
                    courseId = course.id,
                    name = course.name,
                    durationMinutes = reading.durationMinutes,
                    tuitionCoins = reading.tuitionCoins,
                    attributeGain = reading.attributeGain,
                    readingFresh = true,
                    coinsPerMinute = reading.tuitionCoins.toDouble() / reading.durationMinutes,
                    coinsPerAttribute = reading.attributeGain?.let {
                        reading.tuitionCoins.toDouble() / it.amount
                    },
                )
            }
        }
        val tuitionKnown = rows.filter { it.tuitionCoins != null }
        val perMinuteKnown = rows.filter { it.coinsPerMinute != null }
        val perAttributeKnown = rows.filter { it.coinsPerAttribute != null }
        return Summary(
            rows = rows,
            cheapestTuition = tuitionKnown.minByOrNull { it.tuitionCoins ?: Int.MAX_VALUE },
            cheapestPerMinute = perMinuteKnown.minByOrNull { it.coinsPerMinute ?: Double.MAX_VALUE },
            bestAttributeValue = perAttributeKnown.minByOrNull { it.coinsPerAttribute ?: Double.MAX_VALUE },
            unknownNames = rows.filter { it.tuitionCoins == null || it.attributeGain == null }.map { it.name },
        )
    }
}
