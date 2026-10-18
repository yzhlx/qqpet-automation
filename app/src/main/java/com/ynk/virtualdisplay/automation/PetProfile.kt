package com.ynk.virtualdisplay.automation

import android.graphics.PointF
import android.graphics.Rect

/**
 * MVP deliberately supports ONE device / ONE QQ build / ONE 10-minute course.
 * Fill these values after one manual calibration on the validated 720x1600@320dpi display.
 * Any invalid value makes automation refuse to start.
 */
data class PetProfile(
    val displayWidth: Int = 720,
    val displayHeight: Int = 1600,
    val displayDpi: Int = 320,
    val continueButton: PointF = PointF(-1f, -1f),
    val courseButton: PointF = PointF(-1f, -1f),
    val startButton: PointF = PointF(-1f, -1f),
    val resultProbe: Probe = Probe("result", Rect(0, 0, 1, 1), "pet_mvp/result.png"),
    val courseListProbe: Probe = Probe("course_list", Rect(0, 0, 1, 1), "pet_mvp/course_list.png"),
    val courseDetailProbe: Probe = Probe("course_detail", Rect(0, 0, 1, 1), "pet_mvp/course_detail.png"),
    val learningProbes: List<Probe> = emptyList(),
    val matchThreshold: Double = 0.95,
    val pageSettleMs: Long = 900L,
    val courseDurationMs: Long = 10L * 60L * 1000L,
    val courseFinishGraceMs: Long = 8_000L,
) {
    data class Probe(val name: String, val rect: Rect, val assetPath: String)

    fun validate(healthGuardInstalled:Boolean=false): List<String> = buildList {
        if(!healthGuardInstalled) add("健康前置检查未接入：每次开课前体力与清洁必须≥90")
        if (displayWidth != 720 || displayHeight != 1600 || displayDpi != 320) {
            add("MVP只允许720x1600@320dpi")
        }
        listOf("continue" to continueButton, "course" to courseButton, "start" to startButton).forEach { (name, p) ->
            if (p.x < 0f || p.x >= displayWidth.toFloat() || p.y < 0f || p.y >= displayHeight.toFloat()) {
                add("$name 坐标未校准")
            }
        }
        if (learningProbes.size !in 2..3) add("learning需要2至3个实测候选模板")
        (listOf(resultProbe, courseListProbe, courseDetailProbe) + learningProbes).forEach { p ->
            if (p.rect.left < 0 || p.rect.top < 0 || p.rect.right > displayWidth || p.rect.bottom > displayHeight ||
                p.rect.width() < 16 || p.rect.height() < 16) {
                add("${p.name} ROI未校准")
            }
        }
        if (matchThreshold !in 0.70..0.99) add("matchThreshold异常")
    }

    companion object {
        /**
         * Replace ONLY this object after calibration. Keep automation code unchanged.
         */
        fun calibrated(): PetProfile = PetProfile(
            // FOA_AL00 / QQ 9.3.70 (16410), measured 2026-10-05. Only this course.
            continueButton = PointF(530f, 1488f),
            courseButton = PointF(203f, 950f),
            startButton = PointF(360f, 1384f),
            resultProbe = Probe("result", Rect(225, 234, 500, 284), "pet_mvp/result.png"),
            courseListProbe = Probe("course_list", Rect(118, 681, 290, 727), "pet_mvp/course_list.png"),
            courseDetailProbe = Probe("course_detail", Rect(80, 762, 328, 1029), "pet_mvp/course_detail.png"),
            learningProbes = listOf(
                // Game update 2026-10-12: the course now plays on the outdoor map with a bottom
                // "正在学习 / 剩余" panel instead of a classroom page; the old classroom ROIs
                // held at 0.838 for the whole confirm window. Both new probes live on the static
                // panel header (positive 1.0 self-score, <=0.89 on room / course-detail negatives).
                Probe("learning_initial", Rect(60, 925, 420, 1000), "pet_mvp/learning_initial.png"),
                Probe("learning_expanded", Rect(330, 925, 530, 1000), "pet_mvp/learning_expanded.png"),
            ),
        )
    }
}
