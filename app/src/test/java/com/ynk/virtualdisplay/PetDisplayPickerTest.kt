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
}
