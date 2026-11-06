package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PetDisplayPicker
import com.ynk.virtualdisplay.automation.PetDisplayPicker.Candidate
import org.junit.Assert.*
import org.junit.Test

class PetDisplayPickerTest {
    private val phone = listOf(Candidate(28, 720, 1600), Candidate(29, 720, 1600), Candidate(30, 720, 1600))

    @Test fun theScreenQqIsOnWinsOverOlderEmptyScreens() {
        // 2026-11-03: QQ on 30, first match was the empty 28.
        assertEquals(30, PetDisplayPicker.pick(phone, 720, 1600, setOf(28, 29, 30), setOf(30)))
        assertEquals(29, PetDisplayPicker.pick(phone, 720, 1600, setOf(28, 29, 30), setOf(29)))
    }

    @Test fun aManagedQqScreenBeatsAnUnmanagedOne() {
        assertEquals(28, PetDisplayPicker.pick(phone, 720, 1600, setOf(28), setOf(28, 30)))
        assertEquals(30, PetDisplayPicker.pick(phone, 720, 1600, setOf(29), setOf(28, 30)))
    }

    @Test fun withoutQqTheNewestManagedScreenIsUsed() {
        assertEquals(29, PetDisplayPicker.pick(phone, 720, 1600, setOf(28, 29), emptySet()))
        assertEquals(30, PetDisplayPicker.pick(phone, 720, 1600, emptySet(), emptySet()))
    }

    @Test fun onlyTheCalibratedSizeQualifies() {
        val mixed = listOf(Candidate(5, 720, 1600), Candidate(9, 1080, 1920))
        assertEquals(5, PetDisplayPicker.pick(mixed, 720, 1600, setOf(5, 9), setOf(9)))
        assertNull(PetDisplayPicker.pick(listOf(Candidate(9, 1080, 1920)), 720, 1600, setOf(9), setOf(9)))
    }

    @Test fun emptyManagedScreensAreLeftoversOnceQqIsFound() {
        // 2026-11-03: 28 and 29 stayed empty beside QQ on 30.
        assertEquals(listOf(28, 29), PetDisplayPicker.leftovers(phone, 720, 1600, setOf(28, 29, 30), setOf(30), 30))
        assertEquals(listOf(29), PetDisplayPicker.leftovers(phone, 720, 1600, setOf(29, 30), setOf(30), 30))
    }

    @Test fun nothingIsReleasedWithoutProofOfTheQqScreen() {
        assertEquals(emptyList<Int>(), PetDisplayPicker.leftovers(phone, 720, 1600, setOf(28, 29, 30), emptySet(), 30))
        assertEquals(emptyList<Int>(), PetDisplayPicker.leftovers(phone, 720, 1600, setOf(28, 29, 30), setOf(30), null))
        assertEquals(emptyList<Int>(), PetDisplayPicker.leftovers(phone, 720, 1600, setOf(28, 29, 30), setOf(30), 29))
    }

    @Test fun otherSizesAndQqScreensAreKept() {
        val mixed = phone + Candidate(31, 1080, 1920)
        assertEquals(listOf(28), PetDisplayPicker.leftovers(mixed, 720, 1600, setOf(28, 29, 30, 31), setOf(29, 30), 30))
    }

    @Test fun dumpsysSectionsAttributeQqToItsDisplay() {
        val dumpsys = """
            Display #0 (activities from top to bottom):
              * Task{1 #10 type=home A=10010:com.huawei.android.launcher}
                * Hist  #0: ActivityRecord{aa u0 com.huawei.android.launcher/.unihome.UniHomeLauncher t10}
            Display #30 (activities from top to bottom):
                mResumedActivity: ActivityRecord{685b731 u0 com.tencent.mobileqq/com.tencent.robot.adelie.AdelieFragmentActivity t2601}
                  affinity=10230:com.tencent.mobileqq
            Display #28 (activities from top to bottom):
                * Hist  #0: ActivityRecord{bb u0 com.tencent.mobileqqi/.Other t7}
        """.trimIndent()
        assertEquals(setOf(30), PetDisplayPicker.displaysHosting(dumpsys, "com.tencent.mobileqq"))
        assertEquals(setOf(0), PetDisplayPicker.displaysHosting(dumpsys, "com.huawei.android.launcher"))
        assertEquals(emptySet<Int>(), PetDisplayPicker.displaysHosting("", "com.tencent.mobileqq"))
    }

    /**
     * Real `dumpsys activity activities` of the phone 2026-11-07 02:17 with QQ gone from pet screen 49
     * (lines trimmed to the ones that matter, order kept). The orientation bookkeeping line still names
     * a QQ activity, but with task id -1: it is in no task, so QQ is on no screen.
     */
    @Test fun aLeftoverRecordWithoutATaskDoesNotCountAsQqBeingOnTheScreen() {
        val dumpsys = """
            Display #0 (activities from top to bottom):
                mResumedActivity: ActivityRecord{b28b8ea u0 com.ynk.virtualdisplay/.ui.main.MainActivity t2762}
                  mLastFocusedRootTask=Task{da7be29 #2760 type=standard A=10230:com.tencent.mobileqq U=0 visible=false mode=fullscreen translucent=true sz=0}
            Display #49 (activities from top to bottom):
              mLastOrientationSource=DefaultTaskDisplayArea@105441358
              deepestLastOrientationSource=ActivityRecord{65ba6f3 u0 com.tencent.mobileqq/.activity.SplashActivity t-1 f}}
        """.trimIndent()
        val hosting = PetDisplayPicker.displaysHosting(dumpsys, "com.tencent.mobileqq")
        assertEquals(emptySet<Int>(), hosting)
        assertTrue(PetDisplayPicker.shouldRelaunchQq(hosting))
    }

    @Test fun qqIsRelaunchedOnlyWhenItIsOnNoScreenAtAll() {
        assertTrue(PetDisplayPicker.shouldRelaunchQq(emptySet()))
        // On the pet screen already: nothing to do.
        assertFalse(PetDisplayPicker.shouldRelaunchQq(setOf(49)))
        // On the phone's own screen (the user is in QQ): launching it onto the pet screen would pull
        // it away from them, so it is left alone and the session reports what it sees.
        assertFalse(PetDisplayPicker.shouldRelaunchQq(setOf(0)))
    }
}
