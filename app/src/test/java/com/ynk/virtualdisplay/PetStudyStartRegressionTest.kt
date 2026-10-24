package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.StudyPanelText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The running-class panel proof, pinned to what the REAL panel prints (measured 2026-10-21: the
 * pixel template scored 0.703 while the panel read 「正在学习 剩余 00:25:33」). Text is the reliable
 * proof; a lesson that IS running must not be failed just because the pixel template drifted.
 */
class StudyStartRegressionTest {

    @Test fun realRunningPanelTextConfirms() {
        assertTrue(StudyPanelText.confirms(listOf("📖 正在学习", "剩余 00:25:33", "召回")))
        // Split across lines / timer alone on its line also counts.
        assertTrue(StudyPanelText.confirms(listOf("正在学习", "00:09:58")))
    }

    @Test fun aLoneLabelWithoutRunningEvidenceDoesNotConfirm() {
        assertFalse(StudyPanelText.confirms(listOf("正在学习")))
        assertFalse(StudyPanelText.confirms(listOf("剩余 00:25:33")))
        assertFalse(StudyPanelText.confirms(listOf("正在学习", "剩余")))
        assertFalse(StudyPanelText.confirms(listOf("正在学习", "剩余 00:00:00")))
    }

    @Test fun resultAndOtherPagesDoNotConfirm() {
        assertFalse(StudyPanelText.confirms(listOf("继续学习", "成绩 A+", "学费 66")))
        assertFalse(StudyPanelText.confirms(listOf("宠物学园", "职业小镇", "挑战")))
        assertFalse(StudyPanelText.confirms(emptyList()))
    }
}
