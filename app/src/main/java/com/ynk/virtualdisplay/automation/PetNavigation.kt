package com.ynk.virtualdisplay.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.File

/** Only measured page edges, not a general path finder. */
class PetNavigation(private val flow: PetFlowContext) {
    suspend fun dismissCareUpgradeIfPresent(): Boolean = with(flow) {
        val current=frame()
        val matched=try {
            score(current,listOf(careUpgradeHint))>=profile.matchThreshold &&
                score(current,listOf(careUpgradeReward))>=profile.matchThreshold
        } finally {current.recycle()}
        if (!matched) return@with false
        verify(listOf(careUpgradeHint),"care-upgrade-before")
        safety();input.tap(360f,1180f);delay(profile.pageSettleMs)
        verifySettled(listOf(cookie,soap,room),"care-upgrade-after")
        true
    }
    suspend fun tap(before: PetProfile.Probe, point: PointF, after: PetProfile.Probe, action: String) = with(flow) {
        verify(listOf(before),"${serial++}-$action-before",latest=true)
        safety(); input.tap(point.x,point.y)
        delay(profile.pageSettleMs)
        verifySettled(listOf(after),"${serial++}-$action-after")
    }
    suspend fun dismissUpgradeIfPresent(action: String): Boolean = with(flow) {
        val current=frame()
        val upgradeScore=try {score(current,listOf(upgrade))} finally {current.recycle()}
        if(upgradeScore<profile.matchThreshold) return@with false
        emit("$action-upgrade-before",upgradeScore)
        safety();input.tap(360f,1248f);delay(profile.pageSettleMs)
        verifySettled(listOf(academy,profile.courseListProbe),"$action-upgrade-after")
        true
    }
    suspend fun returnHomeFromResult(action: String) = with(flow) {
        verify(listOf(profile.resultProbe),"$action-continue-before",latest=true)
        safety();input.tap(profile.continueButton.x,profile.continueButton.y);delay(profile.pageSettleMs)
        val next=frame()
        val upgraded=try {score(next,listOf(upgrade))>=profile.matchThreshold} finally {next.recycle()}
        if(upgraded) dismissUpgradeIfPresent(action)
        else verifySettled(listOf(academy,profile.courseListProbe),"$action-continue-after")
        val current=frame()
        val panelOpen=try {score(current,listOf(profile.courseListProbe))>=profile.matchThreshold} finally {current.recycle()}
        if(panelOpen) tap(profile.courseListProbe,PointF(80f,160f),academy,"$action-close-course")
        tap(academy,PointF(80f,160f),outdoor,"$action-leave-academy")
        tap(outdoor,PointF(80f,160f),room,"$action-return-home")
    }
    /** Enter the academy map, then explicitly open the unlocked primary academy course panel. */
    suspend fun openAcademyCourseList() = with(flow) {
        state="SELECT_COURSE"
        verify(listOf(outdoor),"academy-entry-before",latest=true)
        safety();input.tap(200f,635f)
        delay(profile.pageSettleMs)
        verifySettled(listOf(academy),"academy-map")
        // Real run 1791201870302 showed the panel was already open before this click;
        // tapping its school label again toggled it closed. Use the existing panel probe.
        val current=frame(latest=true)
        try {
            val panelScore=score(current,listOf(profile.courseListProbe))
            if(panelScore>=profile.matchThreshold) {
                save(current,"academy-course-panel-already-open")
                emit("academy-course-panel-already-open",panelScore,capture.telemetry()
                    .put("templateName",profile.courseListProbe.name).put("result","PASS").toString())
                return@with
            }
            val mapScore=score(current,listOf(academy))
            check(mapScore>=profile.matchThreshold) { "ACADEMY_PAGE_UNCONFIRMED:$mapScore" }
            save(current,"enter-primary-academy-before")
            emit("enter-primary-academy-before",mapScore,capture.telemetry()
                .put("templateName",academy.name).put("result","PASS").toString())
        } finally {current.recycle()}
        safety();input.tap(200f,420f);delay(profile.pageSettleMs)
        verifySettled(listOf(profile.courseListProbe),"enter-primary-academy-after")
    }
    suspend fun initialise() = with(flow) {
        dismissCareUpgradeIfPresent()
        dismissUpgradeIfPresent("initial")
        val current=frame()
        val page=try {
            when {
                score(current,listOf(cookie))>=profile.matchThreshold -> "cookie"
                score(current,listOf(soap))>=profile.matchThreshold -> "soap"
                score(current,listOf(profile.resultProbe))>=profile.matchThreshold -> "result"
                score(current,profile.learningProbes)>=profile.matchThreshold -> "LEARNING"
                score(current,listOf(room))>=profile.matchThreshold && score(current,listOf(healthPanel))<profile.matchThreshold -> "room"
                score(current,listOf(profile.courseListProbe))>=profile.matchThreshold -> "course_list"
                score(current,listOf(academy))>=profile.matchThreshold -> "academy"
                score(current,listOf(outdoor))>=profile.matchThreshold -> "outdoor"
                else -> "UNKNOWN"
            }
        } finally {current.recycle()}
        when(page) {
            "cookie" -> tap(cookie,PointF(80f,160f),room,"initial-leave-food")
            "soap" -> tap(soap,PointF(80f,160f),room,"initial-leave-bath")
            "result" -> returnHomeFromResult("initial-unowned-result")
            "room" -> verify(listOf(room),"initial-home")
            "course_list" -> {tap(profile.courseListProbe,PointF(80f,160f),academy,"initial-close-panel")
                tap(academy,PointF(80f,160f),outdoor,"initial-leave-academy")
                tap(outdoor,PointF(80f,160f),room,"initial-home")}
            "academy" -> {tap(academy,PointF(80f,160f),outdoor,"initial-leave-academy")
                tap(outdoor,PointF(80f,160f),room,"initial-home")}
            "outdoor" -> tap(outdoor,PointF(80f,160f),room,"initial-home")
            "LEARNING" -> error("ACTIVE_COURSE_WITHOUT_OWNED_CHECKPOINT")
            else -> error("INITIAL_PAGE_UNKNOWN")
        }
    }
}
