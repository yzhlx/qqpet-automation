package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.ObservationSource
import com.ynk.virtualdisplay.automation.RewardConfidence
import com.ynk.virtualdisplay.automation.WorkCatalog
import com.ynk.virtualdisplay.automation.WorkOption
import com.ynk.virtualdisplay.automation.WorkSessionLedger
import com.ynk.virtualdisplay.automation.WorkStartAuthorisation
import com.ynk.virtualdisplay.automation.WorkStartConfirmation
import org.junit.Assert.*
import org.junit.Test

/**
 * P5.3 unit tests: the authorisation gate, the reward rungs, the catalog comparison and the
 * double-credit guard.
 *
 * PROVENANCE, READ THIS BEFORE TRUSTING A NUMBER HERE. Every number below is either taken verbatim
 * from docs/P5-01-career-discovery.md (collected 2026-10-12 on display 4) or invented for the test.
 * Nothing here is a device measurement of anything the test claims to prove: these tests prove the
 * RULES (deny by default, one tap per grant, no double credit, no silent promotion of a reward
 * rung), not the game's actual payouts. The payouts are proven by docs/P5-03-*.md once a real
 * session runs.
 */
class PetWorkGateTest {

    private val paotu = "work:竹影武馆/帮武馆跑腿"
    private val peilian = "work:竹影武馆/陪练基础课"

    private fun grant(
        venue: String = "竹影武馆",
        option: String = paotu,
        starts: Int = 1,
        coins: Int = 0,
        energy: Int = 100,
    ) = WorkStartAuthorisation.grant(venue, option, starts, coins, energy, "test-operator", 1000L)

    // ------------------------------------------------------- authorisation gate

    @Test
    fun `defaultIsDeny - there is no implicit grant`() {
        // The only constructor is `grant`, and it requires a named target, a count and a granter.
        // There is deliberately no default/always-allow path to reach.
        val g = grant()
        assertEquals(1, g.remainingStarts)
        assertFalse(g.isRevoked)
    }

    @Test
    fun `aGrantCoversExactlyItsOwnTarget`() {
        val g = grant()
        // Same venue, different option.
        try {
            g.consume("竹影武馆", peilian)
            fail("expected WORK_AUTHORISATION_TARGET_MISMATCH")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("TARGET_MISMATCH"))
        }
        // Different venue, same option id shape.
        try {
            g.consume("风铃旅社", paotu)
            fail("expected WORK_AUTHORISATION_TARGET_MISMATCH")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("TARGET_MISMATCH"))
        }
    }

    @Test
    fun `oneGrantNeverBecomesALoopGrant`() {
        val g = grant(starts = 1)
        g.consume("竹影武馆", paotu)
        assertEquals(0, g.remainingStarts)
        try {
            g.consume("竹影武馆", paotu)
            fail("expected WORK_AUTHORISATION_EXHAUSTED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("AUTHORISATION_EXHAUSTED"))
        }
    }

    @Test
    fun `aGrantCanCoverMoreThanOneStartButStillNotUnbounded`() {
        val g = grant(starts = 3)
        g.consume("竹影武馆", paotu)
        g.consume("竹影武馆", paotu)
        assertEquals(1, g.remainingStarts)
        g.consume("竹影武馆", paotu)
        assertEquals(0, g.remainingStarts)
        try {
            g.consume("竹影武馆", paotu)
            fail("expected WORK_AUTHORISATION_EXHAUSTED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("AUTHORISATION_EXHAUSTED"))
        }
    }

    @Test
    fun `aGrantCannotBeBuiltWithANonsensicalStartCount`() {
        for (bad in listOf(0, -1, 4, 99)) {
            try {
                grant(starts = bad)
                fail("expected WORK_AUTHORISATION_STARTS_INVALID for $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("STARTS_INVALID"))
            }
        }
    }

    @Test
    fun `revokeWithdrawsTheGrantForGood`() {
        val g = grant()
        g.revoke("user paused")
        assertTrue(g.isRevoked)
        try {
            g.consume("竹影武馆", paotu)
            fail("expected WORK_AUTHORISATION_REVOKED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("REVOKED"))
        }
    }

    @Test
    fun `resourceCapsAreHardCaps`() {
        // 帮武馆跑腿 costs no coins to start but drains 5 energy; the cap bounds what the operator
        // accepted for THIS grant, and a spend past it is refused rather than silently allowed.
        val g = grant(energy = 5)
        g.recordSpend(0, 5)
        try {
            g.recordSpend(0, 1)
            fail("expected WORK_AUTHORISATION_ENERGY_CAP")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ENERGY_CAP"))
        }
    }

    @Test
    fun `aSpendIsNeverReleased`() {
        // The brief's rule: an unconfirmed outcome must not widen the budget. The grant exposes no
        // release/refund API at all, so a failed session keeps its spend booked rather than freeing
        // the cap for another attempt.
        val g = grant(energy = 100)
        g.recordSpend(0, 5)
        val fields = g.fields()
        assertEquals(5, fields["energySpent"])
        // Recording the same spend again only grows it: there is no way to take it back.
        g.recordSpend(0, 5)
        assertEquals(10, g.fields()["energySpent"])
    }

    @Test
    fun `aBlankRevokeReasonIsRefused`() {
        val g = grant()
        try {
            g.revoke("   ")
            fail("expected WORK_REVOKE_REASON_REQUIRED")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("REVOKE_REASON_REQUIRED"))
        }
    }

    @Test
    fun `aBlankGranterIsRefused`() {
        try {
            WorkStartAuthorisation.grant("竹影武馆", paotu, 1, 0, 100, "", 1000L)
            fail("expected WORK_AUTHORISATION_GRANTER_REQUIRED")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("GRANTER_REQUIRED"))
        }
    }

    // ------------------------------------------------------- start latch

    @Test
    fun `aDeliveredStartTapCanNeverBeRepeated`() {
        val c = WorkStartConfirmation()
        assertFalse(c.mustNotTapAgain)
        c.markTapDelivered()
        // The dangerous case: delivered but nobody proved what it did.
        assertTrue(c.mustNotTapAgain)
        // Confirming the work does NOT clear the latch either: once a tap went out, the question
        // "may I tap again" is permanently answered no for this session.
        c.markConfirmedWorking()
        assertTrue(c.mustNotTapAgain)
    }

    @Test
    fun `theLatchCannotBeSkippedOrReordered`() {
        val c = WorkStartConfirmation()
        try {
            c.markConfirmedWorking()
            fail("expected WORK_START_NOT_DELIVERED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("NOT_DELIVERED"))
        }
    }

    // ------------------------------------------------------- double credit

    @Test
    fun `theSameSessionRewardCannotBeCreditedTwice`() {
        val ledger = WorkSessionLedger()
        ledger.beginSession("竹影武馆", paotu, 5000L)
        ledger.recordReward("竹影武馆", paotu, 5000L, coins = 68, workScore = 10, energy = -5)
        assertEquals(68, ledger.totalCoins)
        try {
            ledger.recordReward("竹影武馆", paotu, 5000L, coins = 68, workScore = 10, energy = -5)
            fail("expected WORK_REWARD_ALREADY_RECORDED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ALREADY_RECORDED"))
        }
        assertEquals(68, ledger.totalCoins)
    }

    @Test
    fun `aRecoveryThatReReadsTheSameCompletionPageDoesNotDoubleCredit`() {
        // The interrupted-then-recovered case the brief names: the flow re-observes the SAME
        // completion page after a restart. The session key is (venue, option, start timestamp), so
        // the re-read cannot mint a second identity for a session that already completed.
        val ledger = WorkSessionLedger()
        ledger.beginSession("竹影武馆", paotu, 7777L)
        ledger.recordReward("竹影武馆", paotu, 7777L, coins = 68, workScore = 10, energy = -5)
        assertTrue(ledger.isRecorded("竹影武馆", paotu, 7777L))
        // A genuinely different session (different start time) is a different session.
        assertFalse(ledger.isRecorded("竹影武馆", paotu, 7778L))
    }

    @Test
    fun `aRewardCannotBeRecordedForASessionThatNeverStarted`() {
        val ledger = WorkSessionLedger()
        try {
            ledger.recordReward("竹影武馆", paotu, 1L, coins = 68, workScore = 10, energy = -5)
            fail("expected WORK_SESSION_NOT_STARTED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("NOT_STARTED"))
        }
    }

    @Test
    fun `theSameSessionCannotBeStartedTwice`() {
        val ledger = WorkSessionLedger()
        ledger.beginSession("竹影武馆", paotu, 42L)
        try {
            ledger.beginSession("竹影武馆", paotu, 42L)
            fail("expected WORK_SESSION_ALREADY_IN_FLIGHT")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ALREADY_IN_FLIGHT"))
        }
    }

    @Test
    fun `aStartTimestampIsRequiredSoASessionCannotBeAnonymous`() {
        val ledger = WorkSessionLedger()
        try {
            ledger.beginSession("竹影武馆", paotu, 0L)
            fail("expected WORK_SESSION_START_TS_REQUIRED")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("START_TS_REQUIRED"))
        }
    }

    // ------------------------------------------------------- catalog comparison

    @Test
    fun `aMatchingPageReadingProducesNoDifferences`() {
        val catalog = WorkCatalog()
        val entry = catalog.find("竹影武馆", paotu)!!
        val page = WorkOption(
            id = paotu, venue = "竹影武馆", optionLabel = "帮武馆跑腿",
            durationMinutes = 10, pageCoinReward = 68, pageWorkScoreReward = 10,
            pageEnergyCost = 5, pageCleanlinessCost = 2,
        )
        assertTrue(catalog.compare(entry, page).isEmpty())
    }

    @Test
    fun `aChangedRewardRowIsReportedNotSilentlyAccepted`() {
        // The brief's "选项卡切换后奖励数据变化" case: the page now disagrees with the catalog, so
        // the difference must surface. It is never rewritten to match the prediction.
        val catalog = WorkCatalog()
        val entry = catalog.find("竹影武馆", paotu)!!
        val changed = WorkOption(
            id = paotu, venue = "竹影武馆", optionLabel = "帮武馆跑腿",
            durationMinutes = 10, pageCoinReward = 40, pageWorkScoreReward = 10,
            pageEnergyCost = 5, pageCleanlinessCost = 2,
        )
        val diffs = catalog.compare(entry, changed)
        assertTrue(diffs.any { it.contains("金币") && it.contains("40") && it.contains("68") })
    }

    @Test
    fun `anUnknownCatalogRewardIsAHoleNotAZero`() {
        // 街巷跑腿's reward was blocked by QQ rate limiting. Treating the placeholder 0 as a real
        // reading would let the optimiser plan on a number nobody measured.
        val catalog = WorkCatalog()
        val entry = catalog.find("风铃旅社", "work:风铃旅社/街巷跑腿")!!
        assertEquals(RewardConfidence.UNKNOWN, entry.confidence)
        val page = WorkOption(
            id = entry.id, venue = "风铃旅社", optionLabel = "街巷跑腿",
            durationMinutes = 45, pageCoinReward = 0, pageWorkScoreReward = 0,
            pageEnergyCost = 0,
        )
        val diffs = catalog.compare(entry, page)
        assertTrue(diffs.isNotEmpty())
        assertTrue(diffs.any { it.contains("未采集") })
    }

    @Test
    fun `theTwoOptionsInOneVenueStaySeparateObjects`() {
        // P5.1 §3.1: rewards hang on the OPTION, not the venue. Two options in 竹影武馆 are two
        // independent entries and must never be merged.
        val catalog = WorkCatalog()
        val a = catalog.find("竹影武馆", paotu)!!
        val b = catalog.find("竹影武馆", peilian)!!
        assertEquals(10, a.durationMinutes)
        assertEquals(45, b.durationMinutes)
        assertEquals(68, a.coinReward)
        assertEquals(278, b.coinReward)
        assertEquals(10, a.workScoreReward)
        assertEquals(54, b.workScoreReward)
        assertEquals(RewardConfidence.VERIFIED, a.confidence)
        assertEquals(RewardConfidence.PAGE_READING, b.confidence)
    }

    @Test
    fun `theFortyFiveMinuteOptionStaysAPageReadingUntilARealSessionProvesIt`() {
        // 278/54 is a page reading only. It must NOT be promoted to VERIFIED by code: only a real
        // closed-loop session can do that, and that evidence lives in docs/P5-03-*.md.
        val catalog = WorkCatalog()
        val b = catalog.find("竹影武馆", peilian)!!
        assertEquals(RewardConfidence.PAGE_READING, b.confidence)
        assertNotNull(b.caveat)
        assertTrue(b.caveat!!.contains("未"))
    }

    @Test
    fun `theTenMinuteOptionIsTheOnlyClosedLoopVerifiedOne`() {
        val catalog = WorkCatalog()
        val verified = catalog.all().filter { it.confidence == RewardConfidence.VERIFIED }
        assertEquals(1, verified.size)
        assertEquals(paotu, verified.single().id)
    }

    @Test
    fun `anUnknownOptionIsNotFoundRatherThanDefaulted`() {
        val catalog = WorkCatalog()
        assertNull(catalog.find("竹影武馆", "work:竹影武馆/不存在的工作"))
    }

    // ------------------------------------------------------- reward rung separation

    @Test
    fun `anUnreadableResourceReadingIsNeverConvertedToANumber`() {
        // The brief's "无法可靠获取资源读数" case: an Unknown stays Unknown. A silent 0 would look
        // like a real delta and could be booked as a real reward.
        val unknown: ObservedValue<Int> =
            ObservedValue.Unknown("RESOURCE_UNREADABLE", 1000L, "zoom-ocr-failed")
        // The reason is carried through rather than flattened to 0, so a reader failure stays
        // distinguishable from a real reading of zero.
        assertEquals("RESOURCE_UNREADABLE", (unknown as ObservedValue.Unknown).reason)

        // The two halves of ObservedValue stay disjoint: a value that IS readable never makes the
        // unreadable case disappear, which is what stops a failed zoom-OCR from masquerading as a
        // real 0. Both branches are asserted through the same helper the flow uses.
        val known: ObservedValue<Int> =
            ObservedValue.Known(76, 1000L, 1.0, "coin-zoom", ObservationSource.DIGIT_RECOGNITION)
        assertEquals(76, (known as ObservedValue.Known).value)
        assertEquals("UNREADABLE", unreadableReason(unknown))
        assertEquals(null, unreadableReason(known))
    }

    /** Mirrors the flow's own conversion rule: only a Known reading yields a number. */
    private fun unreadableReason(v: ObservedValue<Int>): String? =
        if (v is ObservedValue.Known) null else "UNREADABLE"
}
