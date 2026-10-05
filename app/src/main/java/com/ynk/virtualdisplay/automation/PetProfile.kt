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
    val schoolButton: PointF = PointF(-1f, -1f),
    val courseButton: PointF = PointF(-1f, -1f),
    val startButton: PointF = PointF(-1f, -1f),
    val finishedProbe: Probe = Probe("finished", Rect(0, 0, 1, 1), "pet_mvp/finished.png"),
    val schoolProbe: Probe = Probe("school", Rect(0, 0, 1, 1), "pet_mvp/school.png"),
    val courseProbe: Probe = Probe("course", Rect(0, 0, 1, 1), "pet_mvp/course.png"),
    val learningProbe: Probe = Probe("learning", Rect(0, 0, 1, 1), "pet_mvp/learning.png"),
    val matchThreshold: Double = 0.90,
    val pageSettleMs: Long = 900L,
    val courseDurationMs: Long = 10L * 60L * 1000L,
    val courseFinishGraceMs: Long = 8_000L,
) {
    data class Probe(val name: String, val rect: Rect, val assetPath: String)

    fun validate(): List<String> = buildList {
        if (displayWidth != 720 || displayHeight != 1600 || displayDpi != 320) {
            add("MVP只允许720x1600@320dpi")
        }
        listOf("school" to schoolButton, "course" to courseButton, "start" to startButton).forEach { (name, p) ->
            if (p.x < 0f || p.x >= displayWidth.toFloat() || p.y < 0f || p.y >= displayHeight.toFloat()) {
                add("$name 坐标未校准")
            }
        }
        listOf(finishedProbe, schoolProbe, courseProbe, learningProbe).forEach { p ->
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
            // TODO: replace the three coordinates and four Rects after real-device calibration.
            schoolButton = PointF(-1f, -1f),
            courseButton = PointF(-1f, -1f),
            startButton = PointF(-1f, -1f),
            finishedProbe = Probe("finished", Rect(0, 0, 1, 1), "pet_mvp/finished.png"),
            schoolProbe = Probe("school", Rect(0, 0, 1, 1), "pet_mvp/school.png"),
            courseProbe = Probe("course", Rect(0, 0, 1, 1), "pet_mvp/course.png"),
            learningProbe = Probe("learning", Rect(0, 0, 1, 1), "pet_mvp/learning.png"),
        )
    }
}
