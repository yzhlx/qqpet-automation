package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.QuietWindow
import com.ynk.virtualdisplay.automation.QuietWindowPolicy
import com.ynk.virtualdisplay.automation.SessionMode
import com.ynk.virtualdisplay.automation.SessionStartGate
import com.ynk.virtualdisplay.ui.screens.QuietWindowPresentation
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** STR-17 夜间暂停窗口：判定必须只依赖时间，不能依赖设备状态。 */
class QuietWindowTest {
    private val night = QuietWindow(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60)
    private val daytime = QuietWindow(enabled = true, startMinute = 1 * 60, endMinute = 5 * 60)

    @Test fun aDisabledWindowNeverPauses() {
        assertFalse(QuietWindowPolicy.isQuiet(23 * 60, night.copy(enabled = false)))
        assertFalse(QuietWindowPolicy.isQuiet(3 * 60, night.copy(enabled = false)))
    }

    @Test fun aWindowCrossingMidnightCoversBothSides() {
        assertTrue(QuietWindowPolicy.isQuiet(23 * 60, night))
        assertTrue(QuietWindowPolicy.isQuiet(23 * 60 + 59, night))
        assertTrue(QuietWindowPolicy.isQuiet(0, night))
        assertTrue(QuietWindowPolicy.isQuiet(6 * 60 + 59, night))
        assertFalse(QuietWindowPolicy.isQuiet(7 * 60, night))
        assertFalse(QuietWindowPolicy.isQuiet(22 * 60 + 59, night))
    }

    @Test fun aWindowInsideOneDayCoversOnlyThatSpan() {
        assertTrue(QuietWindowPolicy.isQuiet(1 * 60, daytime))
        assertTrue(QuietWindowPolicy.isQuiet(4 * 60 + 59, daytime))
        assertFalse(QuietWindowPolicy.isQuiet(5 * 60, daytime))
        assertFalse(QuietWindowPolicy.isQuiet(0, daytime))
        assertFalse(QuietWindowPolicy.isQuiet(23 * 60, daytime))
    }

    @Test fun theSameStartAndEndMeansNoWindowAtAll() {
        val same = QuietWindow(enabled = true, startMinute = 3 * 60, endMinute = 3 * 60)
        assertFalse(QuietWindowPolicy.isQuiet(3 * 60, same))
        assertFalse(QuietWindowPolicy.isQuiet(0, same))
    }

    @Test fun minutesUntilEndCountsDownAndWrapsOverMidnight() {
        assertEquals(8 * 60, QuietWindowPolicy.minutesUntilEnd(23 * 60, night))
        assertEquals(7 * 60, QuietWindowPolicy.minutesUntilEnd(0, night))
        assertEquals(1, QuietWindowPolicy.minutesUntilEnd(6 * 60 + 59, night))
        assertEquals(0, QuietWindowPolicy.minutesUntilEnd(12 * 60, night))
        assertEquals(120, QuietWindowPolicy.minutesUntilEnd(3 * 60, daytime))
    }

    @Test fun theWindowTextIsWhatTheUserReads() {
        assertEquals("23:00", night.startText())
        assertEquals("07:00", night.endText())
        assertTrue(night.spansMidnight())
        assertFalse(daytime.spansMidnight())
    }

    @Test fun minutesAreTakenFromTheWallClockInTheUserTimeZone() {
        assertEquals(0, QuietWindowPolicy.nowMinute(LocalTime.of(0, 0)))
        assertEquals(23 * 60 + 30, QuietWindowPolicy.nowMinute(LocalTime.of(23, 30)))
    }

    @Test fun theStartGateRefusesInsideTheWindowAndExplainsWhenItOpens() {
        val decision = SessionStartGate.evaluate(night, 23 * 60 + 30, SessionMode.FULL)
        assertTrue(decision is SessionStartGate.Decision.Refused)
        val message = (decision as SessionStartGate.Decision.Refused).message
        assertTrue(message.contains("23:00"))
        assertTrue(message.contains("07:00"))
        assertTrue(message.contains("到第二天 07:00"))
    }

    @Test fun theStartGateLetsTheSessionRunOutsideTheWindow() {
        assertEquals(SessionStartGate.Decision.Proceed, SessionStartGate.evaluate(night, 12 * 60, SessionMode.FULL))
        assertEquals(SessionStartGate.Decision.Proceed, SessionStartGate.evaluate(night.copy(enabled = false), 0, SessionMode.FULL))
    }

    @Test fun aHealthCheckIsNeverBlockedBecauseItOnlyReads() {
        assertEquals(SessionStartGate.Decision.Proceed, SessionStartGate.evaluate(night, 23 * 60 + 30, SessionMode.HEALTH_CHECK))
    }

    @Test fun anOutOfRangeMinuteIsRejectedInsteadOfSilentlyWrapping() {
        var failed = false
        try {
            QuietWindow(enabled = true, startMinute = 1440, endMinute = 60)
        } catch (error: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    /** 主页要能直接看到「今晚不托管」，不用进设置里找。 */
    @Test fun theHomeLineSaysWhenThePauseEnds() {
        assertEquals("夜间暂停中 · 到第二天 07:00 再开始",
            QuietWindowPresentation.statusLine(night, 23 * 60 + 30))
        assertEquals("夜间暂停中 · 到第二天 07:00 再开始",
            QuietWindowPresentation.statusLine(night, 0))
    }

    @Test fun theHomeLineAlsoTellsTheUserItCanStartNow() {
        assertEquals("夜间暂停 23:00–07:00 · 现在可以开始",
            QuietWindowPresentation.statusLine(night, 12 * 60))
        assertFalse(QuietWindowPresentation.isQuietNow(night, 12 * 60))
    }

    /** 没有窗口就不占一行；起止相同也不是窗口，别让主页多出一句废话。 */
    @Test fun thereIsNoLineWhenThereIsNoWindow() {
        assertNull(QuietWindowPresentation.statusLine(night.copy(enabled = false), 23 * 60))
        assertNull(QuietWindowPresentation.statusLine(
            QuietWindow(enabled = true, startMinute = 60, endMinute = 60), 60))
    }

    /** 当天内的窗口不能写「第二天」——那句话只在跨零点时成立。 */
    @Test fun aDaytimeWindowDoesNotMentionTheSecondDay() {
        assertEquals("夜间暂停中 · 到 05:00 再开始",
            QuietWindowPresentation.statusLine(daytime, 3 * 60))
        assertEquals("夜间暂停 01:00–05:00 · 现在可以开始",
            QuietWindowPresentation.statusLine(daytime, 12 * 60))
    }
}
