package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ObservationSource
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.StableRoomCoins
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A coin count read off a rolling-up top bar counts only when two reads in a row agree. */
class StableRoomCoinsTest {

    private fun known(v: Int) = ObservedValue.Known(v, 1L, 0.9, "top-bar", ObservationSource.DIGIT_RECOGNITION)
    private fun unknown() = ObservedValue.Unknown("COIN_REGION_UNREAD", 1L, "top-bar")

    private class Script(private val reads: List<ObservedValue<Int>>) {
        var index = 0
        var waits = 0
        suspend fun observe(): ObservedValue<Int> = reads[minOf(index++, reads.lastIndex)]
        suspend fun wait(@Suppress("UNUSED_PARAMETER") ms: Long) { waits++ }
    }

    private fun run(reads: List<ObservedValue<Int>>, maxReads: Int = 5): Pair<ObservedValue<Int>, Script> {
        val script = Script(reads)
        val result = runBlocking {
            StableRoomCoins.read("t", maxReads, 1L, script::wait, script::observe)
        }
        return result to script
    }

    @Test fun aHalfRolledUpZeroIsNotTrusted() {
        // Device log 2026-11-07 02:29: first frame read 0 while the balance was 636.
        val (result, script) = run(listOf(known(0), known(636), known(636)))
        assertEquals(636, (result as ObservedValue.Known).value)
        assertEquals(3, script.index)
    }

    @Test fun twoAgreeingReadsAreEnough() {
        val (result, script) = run(listOf(known(660), known(660)))
        assertEquals(660, (result as ObservedValue.Known).value)
        assertEquals(2, script.index)
        assertEquals(1, script.waits)
    }

    @Test fun aBalanceThatIsReallyZeroIsStillReported() {
        val (result, _) = run(listOf(known(0), known(0)))
        assertEquals(0, (result as ObservedValue.Known).value)
    }

    @Test fun aCountThatNeverSettlesIsUnknownNotTheLastGuess() {
        val (result, script) = run(listOf(known(0), known(120), known(300), known(480), known(636)))
        assertTrue(result is ObservedValue.Unknown)
        assertEquals("ROOM_COINS_UNSTABLE:last=636", (result as ObservedValue.Unknown).reason)
        assertEquals(5, script.index)
    }

    @Test fun unreadFramesBetweenReadingsDoNotCountAsAgreement() {
        val (result, _) = run(listOf(known(636), unknown(), known(636), known(636)))
        assertEquals(636, (result as ObservedValue.Known).value)
    }

    @Test fun anUnreadableBarStaysUnreadable() {
        val (result, script) = run(listOf(unknown()))
        assertTrue(result is ObservedValue.Unknown)
        assertEquals("COIN_REGION_UNREAD", (result as ObservedValue.Unknown).reason)
        assertEquals(5, script.index)
    }
}
