package com.ynk.virtualdisplay.bot

import android.graphics.Bitmap
import com.ynk.virtualdisplay.automation.PetPageProbes
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.TemplateMatcher
import com.ynk.virtualdisplay.automation.WorkPageParser

/**
 * Template scores for every probe [PageClassifier] reads, from one frame. Reuses the measured
 * probes and assets of the earlier implementation unchanged.
 */
class ProbeSet(private val matcher: TemplateMatcher) {
    private val profile = PetProfile.calibrated()
    private val single: Map<String, PetProfile.Probe> = mapOf(
        "qq_host_search" to PetPageProbes.qqHostSearch,
        "upgrade" to PetPageProbes.upgrade,
        "result" to profile.resultProbe,
        "food_scene" to PetPageProbes.foodScene,
        "bath" to PetPageProbes.bath,
        "health" to PetPageProbes.healthPanel,
        "course_detail" to profile.courseDetailProbe,
        "course_list" to profile.courseListProbe,
        "academy" to PetPageProbes.academy,
        "room" to PetPageProbes.room,
        "outdoor" to PetPageProbes.outdoor,
    )

    private fun score(frame: Bitmap, probe: PetProfile.Probe) =
        matcher.match(frame, probe, PageClassifier.THRESHOLD).score

    fun scores(frame: Bitmap): Map<String, Double> = buildMap {
        for ((name, probe) in single) put(name, score(frame, probe))
        // The learning panel has two measured looks; either one counts.
        put("learning", profile.learningProbes.maxOf { score(frame, it) })
        // The care popup is only proven when both its hint and its reward row are present.
        put("care_upgrade", minOf(score(frame, PetPageProbes.careUpgradeHint), score(frame, PetPageProbes.careUpgradeReward)))
    }
}

/**
 * [Eyes] on the real virtual display.
 *
 * @param frame captures one fresh frame; the caller owns the capture session.
 * @param isTown OCR check for career-town pages, which have no template (see [townCheck]).
 */
class DeviceEyes(
    private val frame: suspend () -> Bitmap,
    private val probes: ProbeSet,
    private val isTown: (suspend (Bitmap) -> Boolean)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : Eyes {
    override suspend fun look(): Observation {
        val current = frame()
        try {
            val scores = probes.scores(current)
            val page = PageClassifier.classify(scores)
            val resolved = if (page == Page.UNKNOWN && isTown?.invoke(current) == true) Page.TOWN else page
            return Observation(resolved, clock())
        } finally {
            current.recycle()
        }
    }
}

/** OCR check for the career town's list, detail, working and payroll pages. */
fun townCheck(ocr: com.ynk.virtualdisplay.automation.CourseNameReader): suspend (Bitmap) -> Boolean = { frame ->
    val lines = ocr.read(frame)
    val text = lines.joinToString(" ") { it.text }
    WorkPageParser.parseTownPage(lines) != WorkPageParser.TownPageKind.NONE ||
        text.contains("打工中") || WorkPageParser.PAYROLL_MARKERS.any { text.contains(it) }
}
