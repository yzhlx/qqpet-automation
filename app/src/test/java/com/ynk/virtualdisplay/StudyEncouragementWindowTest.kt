package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.StudyEncouragementWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StudyEncouragementWindowTest {
    @Test fun confirmedPanelDoesNotAllowAnEarlyInput() = runBlocking {
        var now=1_000L
        var observed=0
        StudyEncouragementWindow.await(0L,{now}, {observed++; true}, {now+=it})
        val firstInputAt=now
        assertTrue("First encouragement must wait until course T+30s, got $firstInputAt", firstInputAt>=30_000L)
        assertTrue(observed>0)
    }
    @Test fun delayedRecognitionDoesNotResetCourseClock() = runBlocking {
        var now=29_400L
        var totalWait=0L
        StudyEncouragementWindow.await(0L,{now}, {now+=250; true}, {now+=it;totalWait+=it})
        assertEquals(30_000L,now)
        assertEquals(350L,totalWait)
    }
    @Test fun lateEntryDoesNotWaitAnotherThirtySeconds() = runBlocking {
        val now=35_000L
        StudyEncouragementWindow.await(0L,{now}, {fail("No extra observation needed"); false}, {fail("No extra wait needed")})
    }
}
