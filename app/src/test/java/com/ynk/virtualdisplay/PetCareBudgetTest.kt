package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CareConsumableBudget
import com.ynk.virtualdisplay.automation.ConsumableReservation
import com.ynk.virtualdisplay.automation.InjectAttemptLedger
import com.ynk.virtualdisplay.automation.ReservationOutcome
import com.ynk.virtualdisplay.automation.ReservationState
import com.ynk.virtualdisplay.automation.consumableDeliveryEvidence
import com.ynk.virtualdisplay.automation.settleAllowanceAfterFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The batch-wide consumable limit. The per-recover retry caps (5 feeds / 3 cleans) are not the
 * authorization limit: recover() runs once per round, so they must not be able to accumulate into
 * 15 cookies / 9 soaps over a 3-round session. Pure JVM: no Android types are involved.
 */
class PetCareBudgetTest {
    private fun messageOf(block: () -> Unit): String =
        runCatching(block).exceptionOrNull()?.message
            ?: fail("expected the budget to refuse this consume").toString()

    private fun assertRefused(marker: String, block: () -> Unit) =
        assertTrue("expected $marker, got '${messageOf(block)}'", messageOf(block).contains(marker))

    @Test fun defaultsMatchTheAuthorisedBatchLimits() {
        val budget = CareConsumableBudget()
        assertEquals(6, budget.cookies)
        assertEquals(4, budget.soaps)
        assertEquals(0, budget.cookiesUsed)
        assertEquals(0, budget.soapsUsed)
    }

    @Test fun accumulationSpansMultipleRecoverCalls() {
        val budget = CareConsumableBudget()
        // Round 1 spends 2 cookies and 1 soap.
        budget.consumedCookie(); budget.consumedCookie(); budget.consumedSoap()
        // Round 2 continues on the same budget rather than restarting at zero.
        assertEquals(2, budget.cookiesUsed)
        assertEquals(1, budget.soapsUsed)
        budget.consumedCookie()
        assertEquals(3, budget.cookiesUsed)
    }

    @Test fun theBoundaryIsExactlyFiveCookiesAndThreeSoaps() {
        val budget = CareConsumableBudget()
        repeat(6) { budget.consumedCookie() }
        assertEquals(6, budget.cookiesUsed)
        assertRefused("CARE_COOKIE_BUDGET_EXHAUSTED") { budget.consumedCookie() }
        assertEquals(6, budget.cookiesUsed)

        repeat(4) { budget.consumedSoap() }
        assertEquals(4, budget.soapsUsed)
        assertRefused("CARE_SOAP_BUDGET_EXHAUSTED") { budget.consumedSoap() }
        assertEquals(4, budget.soapsUsed)
    }

    @Test fun theRefusalHappensBeforeANewConsumableActionBegins() {
        val budget = CareConsumableBudget(cookies = 1, soaps = 1)
        budget.requireCookieAvailable()
        budget.consumedCookie()
        // The guard that precedes the next action refuses it; nothing further is spent.
        assertRefused("CARE_COOKIE_BUDGET_EXHAUSTED") { budget.requireCookieAvailable() }
        assertEquals(1, budget.cookiesUsed)

        budget.requireSoapAvailable()
        budget.consumedSoap()
        assertRefused("CARE_SOAP_BUDGET_EXHAUSTED") { budget.requireSoapAvailable() }
        assertEquals(1, budget.soapsUsed)
    }

    @Test fun exhaustingOneConsumableDoesNotBlockTheOther() {
        val budget = CareConsumableBudget(cookies = 1, soaps = 3)
        budget.consumedCookie()
        assertRefused("CARE_COOKIE_BUDGET_EXHAUSTED") { budget.requireCookieAvailable() }
        budget.requireSoapAvailable()
        budget.consumedSoap()
        assertEquals(1, budget.soapsUsed)
    }

    @Test fun aSessionOfRepeatedRecoversCanNotExceedTheBatchLimits() {
        val budget = CareConsumableBudget()
        var refusals = 0
        // Three rounds, each attempting the full per-recover allowance of 5 cookies and 3 soaps.
        repeat(3) {
            repeat(5) {
                if (runCatching { budget.consumedCookie() }.isFailure) refusals++
            }
            repeat(3) {
                if (runCatching { budget.consumedSoap() }.isFailure) refusals++
            }
        }
        assertEquals(6, budget.cookiesUsed)
        assertEquals(4, budget.soapsUsed)
        assertEquals("9 cookies + 5 soaps must be refused", 14, refusals)
    }

    @Test fun anInvalidBudgetIsRejectedRatherThanSilentlyUnlimited() {
        assertRefused("CARE_BUDGET_INVALID") { CareConsumableBudget(cookies = 0) }
        assertRefused("CARE_BUDGET_INVALID") { CareConsumableBudget(soaps = 0) }
    }

    // ---- an allowance is taken BEFORE the action, so a failure can not widen the batch cap ----

    @Test fun anAllowanceTakenBeforeTheActionIsCountedImmediately() {
        val budget = CareConsumableBudget()
        val reservation = budget.reserveCookie()
        // Counted at reservation time: the budget must already show it as spent-or-possibly-spent.
        assertEquals(1, budget.cookiesUsed)
        assertEquals(ReservationState.RESERVED, reservation.state)
        reservation.confirmConsumed()
        assertEquals(ReservationState.CONSUMED, reservation.state)
        assertEquals(1, budget.cookiesUsed)
    }

    @Test fun anUnconfirmedDeliveryKeepsTheConsumableSpentRatherThanRefundingIt() {
        val budget = CareConsumableBudget()
        val reservation = budget.reserveCookie()
        // The cookie input verifiably reached the game but the result could not be confirmed: the
        // item may already be gone, so the allowance may NOT be returned.
        assertEquals(ReservationOutcome.RETAINED_UNCONFIRMED, reservation.settleAfterFailure(deliveredEvents = 2))
        assertEquals(1, budget.cookiesUsed)
        assertEquals(ReservationState.CONSUMED, reservation.state)
    }

    @Test fun onlyAProvenUndeliveredActionReturnsItsAllowance() {
        val budget = CareConsumableBudget()
        val reservation = budget.reserveSoap()
        // No event reached the display, so nothing could have consumed the soap.
        assertEquals(ReservationOutcome.RELEASED, reservation.settleAfterFailure(deliveredEvents = 0))
        assertEquals(0, budget.soapsUsed)
        assertEquals(ReservationState.RELEASED, reservation.state)
        // ...and the released allowance is genuinely usable again.
        budget.reserveSoap().confirmConsumed()
        assertEquals(1, budget.soapsUsed)
    }

    @Test fun settlingAnAlreadyConfirmedAllowanceNeverReDecidesOrRefundsIt() {
        val budget = CareConsumableBudget()
        val reservation = budget.reserveCookie()
        reservation.confirmConsumed()
        // A later failure path must not be able to turn a confirmed spend into a refund.
        assertEquals(ReservationOutcome.CONSUMED, reservation.settleAfterFailure(deliveredEvents = 0))
        assertEquals(1, budget.cookiesUsed)
        assertRefused("CARE_RESERVATION_NOT_ACTIVE") { reservation.releaseUnused("NO_INPUT_DELIVERED") }
        assertRefused("CARE_RESERVATION_NOT_ACTIVE") { reservation.confirmConsumed() }
    }

    @Test fun repeatedFailuresAcrossRecoversCanNotPushTheTotalPastTheBatchLimit() {
        val budget = CareConsumableBudget()
        var attempts = 0
        var retained = 0
        // Three rounds, each retrying the per-recover allowance; every retry fails after delivery.
        repeat(3) {
            repeat(6) {
                attempts++
                val reservation = runCatching { budget.reserveCookie() }.getOrNull() ?: return@repeat
                if (reservation.settleAfterFailure(deliveredEvents = 1) == ReservationOutcome.RETAINED_UNCONFIRMED) retained++
            }
        }
        assertEquals(6, budget.cookiesUsed)
        assertEquals(">6 attempts were made, so refusals must have occurred", 6, retained)
        assertTrue(attempts > 6)
        assertRefused("CARE_COOKIE_BUDGET_EXHAUSTED") { budget.reserveCookie() }
    }

    @Test fun aRetryThatFirstFailedUndeliveredCanStillUseItsReleasedAllowance() {
        val budget = CareConsumableBudget(cookies = 2)
        // Attempt 1 never reached the game, so the allowance is returned and attempt 2 may take it.
        budget.reserveCookie().settleAfterFailure(deliveredEvents = 0)
        assertEquals(0, budget.cookiesUsed)
        budget.reserveCookie().confirmConsumed()
        assertEquals(1, budget.cookiesUsed)
        // Attempt 3 is a genuine second spend, and it is the last one allowed.
        budget.reserveCookie().confirmConsumed()
        assertEquals(2, budget.cookiesUsed)
        assertRefused("CARE_COOKIE_BUDGET_EXHAUSTED") { budget.reserveCookie() }
    }

    @Test fun aReleaseMustStateWhyAndOnlyHappensOnce() {
        val budget = CareConsumableBudget()
        val reservation = budget.reserveCookie()
        assertRefused("CARE_RELEASE_REASON_REQUIRED") { reservation.releaseUnused("  ") }
        reservation.releaseUnused("NO_INPUT_DELIVERED")
        assertEquals(0, budget.cookiesUsed)
        // A second release of the same allowance would corrupt the count.
        assertRefused("CARE_RESERVATION_NOT_ACTIVE") { reservation.releaseUnused("NO_INPUT_DELIVERED") }
        assertEquals(0, budget.cookiesUsed)
    }

    @Test fun reservedAllowancesAreVisibleInTheSameAccumulatorsTheSessionReports() {
        val budget = CareConsumableBudget()
        val cookie = budget.reserveCookie()
        val soap = budget.reserveSoap()
        // The counters the session journals must include reservations, not only confirmed spends.
        assertEquals(1, budget.cookiesUsed)
        assertEquals(1, budget.soapsUsed)
        cookie.confirmConsumed()
        soap.settleAfterFailure(deliveredEvents = 0)
        assertEquals(1, budget.cookiesUsed)
        assertEquals(0, budget.soapsUsed)
    }

    // ---- the settlement decision itself, as the care flow applies it ----

    @Test fun settlementJournalMatchesWhatHappenedToTheAllowance() {
        val budget = CareConsumableBudget()
        val journaled = mutableListOf<Triple<String, ReservationState, String?>>()
        val journal: (String, ConsumableReservation, String?) -> Unit = { action, held, reason ->
            journaled += Triple(action, held.state, reason)
        }
        // Delivered but unconfirmed: kept spent, and that is on record.
        val retained = budget.reserveCookie()
        settleAllowanceAfterFailure(retained, deliveredEvents = 2, action = "feed", journal = journal)
        assertEquals(listOf(Triple("feed-unconfirmed", ReservationState.CONSUMED, "DELIVERED_BUT_UNCONFIRMED")), journaled)
        // Nothing delivered: returned, and that is on record too.
        val released = budget.reserveSoap()
        settleAllowanceAfterFailure(released, deliveredEvents = 0, action = "clean", journal = journal)
        assertEquals(Triple("clean-release", ReservationState.RELEASED, "NO_INPUT_DELIVERED"), journaled[1])
        assertEquals(1, budget.cookiesUsed)
        assertEquals(0, budget.soapsUsed)
    }

    @Test fun settlementWritesNothingWhenTheSpendWasAlreadyConfirmedBySuccess() {
        val budget = CareConsumableBudget()
        val journaled = mutableListOf<String>()
        val reservation = budget.reserveCookie()
        reservation.confirmConsumed()
        // The success path already journaled the consume; the failure handler must stay silent.
        settleAllowanceAfterFailure(reservation, deliveredEvents = 3, action = "feed",
            journal = { action, _, _ -> journaled += action })
        assertTrue("a confirmed spend must not be re-journaled: $journaled", journaled.isEmpty())
        assertEquals(1, budget.cookiesUsed)
    }

    @Test fun aFailedActionBeforeTheConsumableInputCanNotClaimADelivery() {
        val budget = CareConsumableBudget()
        val journaled = mutableListOf<String>()
        val reservation = budget.reserveCookie()
        // Navigation input may have reached the display, but the consumable tap never started, so the
        // flow passes 0 rather than the display's event count - and the allowance comes back.
        settleAllowanceAfterFailure(reservation, deliveredEvents = 0, action = "feed",
            journal = { action, _, _ -> journaled += action })
        assertEquals(listOf("feed-release"), journaled)
        assertEquals(0, budget.cookiesUsed)
    }

    // ---- the release decision fed by a transport that APPLIES an event and then fails its ack ----
    //
    // settleAllowanceAfterFailure() takes a count, so a careless caller could pass the ack count and
    // refund a cookie the game already spent. These tests drive the decision from the real
    // attempt-before-inject ledger, with a fake transport that records its side effect first, so the
    // "zero acknowledgements" that must NOT release anything is actually produced by execution.

    /** Applies the event, then fails the acknowledgement. */
    private class SideEffectTransport(private val mode: String) {
        val applied = mutableListOf<String>()
        fun inject(action: String): Boolean {
            applied += action
            when (mode) {
                "false" -> Unit          // applied, then returns false
                "throw" -> throw IllegalStateException("TRANSPORT_FAULT")
                else -> Unit
            }
            return mode == "ok"
        }
    }

    /** true when the inject call was acknowledged, null when it was refused (false/throw). */
    private fun attemptDown(ledger: InjectAttemptLedger, transport: SideEffectTransport): Boolean? =
        runBlocking {
            runCatching { ledger.attempt(isDown = true, rejected = "ACTION_DOWN rejected") { transport.inject("DOWN") } }
                .fold({ true }, { null })
        }

    @Test fun anUnacknowledgedTransportCallThatHappenedKeepsTheAllowanceSpent() {
        listOf("false", "throw").forEach { mode ->
            val budget = CareConsumableBudget()
            val journaled = mutableListOf<String>()
            val ledger = InjectAttemptLedger()
            val transport = SideEffectTransport(mode)
            val before = ledger.attempts
            val reservation = budget.reserveCookie()
            // The real code path: the attempt is recorded before the inject call, which applies the
            // DOWN and then refuses to acknowledge it.
            val accepted = attemptDown(ledger, transport)
            assertNull("a $mode ack must not be accepted as delivery", accepted)
            assertEquals("the transport DID apply the DOWN", listOf("DOWN"), transport.applied)
            assertEquals("zero acknowledgements is not zero attempts", 1, ledger.attempts - before)
            settleAllowanceAfterFailure(reservation,
                deliveredEvents = if (ledger.attempts > before) 1 else 0, action = "feed",
                journal = { action, _, _ -> journaled += action })
            assertEquals("a possibly-spent cookie must not be refunded on a $mode ack",
                listOf("feed-unconfirmed"), journaled)
            assertEquals(1, budget.cookiesUsed)
            assertEquals(ReservationState.CONSUMED, reservation.state)
        }
    }

    @Test fun aPreInjectionRefusalReleasesAndTheTotalNeverRegresses() {
        val budget = CareConsumableBudget(cookies = 2)
        val journaled = mutableListOf<String>()
        val ledger = InjectAttemptLedger()

        // Attempt 1: the freshness/pause guard refused before any inject call started. The flow read
        // its baseline immediately before the consumable action and no call followed it.
        val first = budget.reserveCookie()
        val firstBaseline = ledger.attempts
        settleAllowanceAfterFailure(first,
            deliveredEvents = consumableDeliveryEvidence(true, firstBaseline, ledger.attempts),
            action = "feed", journal = { action, _, _ -> journaled += action })
        assertEquals(listOf("feed-release"), journaled)
        assertEquals("a provably undelivered allowance comes back", 0, budget.cookiesUsed)

        // Attempt 2 applies a DOWN and fails its ack: the spend is now on record and stays on record.
        val second = budget.reserveCookie()
        assertEquals(1, budget.cookiesUsed)
        val secondBaseline = ledger.attempts
        assertNull(attemptDown(ledger, SideEffectTransport("false")))
        settleAllowanceAfterFailure(second,
            deliveredEvents = consumableDeliveryEvidence(true, secondBaseline, ledger.attempts),
            action = "feed", journal = { action, _, _ -> journaled += action })
        assertEquals(listOf("feed-release", "feed-unconfirmed"), journaled)
        assertEquals("the total must not regress after the unconfirmed delivery", 1, budget.cookiesUsed)

        // Re-settling the same allowance can not refund it a second time.
        settleAllowanceAfterFailure(second, deliveredEvents = 0, action = "feed",
            journal = { action, _, _ -> journaled += action })
        assertEquals(1, budget.cookiesUsed)

        // The last allowance is the only one left, and the next one is refused BEFORE any input.
        val third = budget.reserveCookie()
        val thirdBaseline = ledger.attempts
        assertNull(attemptDown(ledger, SideEffectTransport("throw")))
        settleAllowanceAfterFailure(third,
            deliveredEvents = consumableDeliveryEvidence(true, thirdBaseline, ledger.attempts),
            action = "feed", journal = { action, _, _ -> journaled += action })
        assertEquals(2, budget.cookiesUsed)
        assertRefused("CARE_COOKIE_BUDGET_EXHAUSTED") { budget.requireCookieAvailable() }
    }

    // ---- the flow-side evidence rule: stale navigation events must not be charged to the cookie ----

    @Test fun navigationInputBeforeAConsumableActionIsNotEvidenceOfItsDelivery() {
        val budget = CareConsumableBudget()
        val journaled = mutableListOf<String>()
        val ledger = InjectAttemptLedger()
        val reservation = budget.reserveCookie()
        // Two acknowledged navigation taps happen, then safety() refuses the cookie tap before it can
        // start any inject call: the baseline is read at the consumable action, so those navigation
        // events are NOT charged to this allowance and it comes back.
        runBlocking {
            ledger.attempt(isDown = false, rejected = "r") { true }
            ledger.attempt(isDown = false, rejected = "r") { true }
        }
        val baseline = ledger.attempts
        assertEquals(0, consumableDeliveryEvidence(true, baseline, ledger.attempts))
        settleAllowanceAfterFailure(reservation,
            deliveredEvents = consumableDeliveryEvidence(true, baseline, ledger.attempts),
            action = "feed", journal = { action, _, _ -> journaled += action })
        assertEquals(listOf("feed-release"), journaled)
        assertEquals(0, budget.cookiesUsed)
    }

    @Test fun aFailureBeforeTheConsumableActionIsReachedAlsoReleases() {
        val budget = CareConsumableBudget()
        val journaled = mutableListOf<String>()
        val ledger = InjectAttemptLedger()
        val reservation = budget.reserveCookie()
        // consumableAttempted=false: the flow failed during navigation or page verification. Even if
        // inject calls happened, none of them could have spent this item.
        runBlocking { ledger.attempt(isDown = true, rejected = "r") { true } }
        assertEquals(0, consumableDeliveryEvidence(false, 0, ledger.attempts))
        settleAllowanceAfterFailure(reservation,
            deliveredEvents = consumableDeliveryEvidence(false, 0, ledger.attempts),
            action = "feed", journal = { action, _, _ -> journaled += action })
        assertEquals(listOf("feed-release"), journaled)
        assertEquals(0, budget.cookiesUsed)
    }

    @Test fun aStaleBaselineOverRetainsInsteadOfRefundingAPossiblySpentItem() {
        // A baseline read after the action (a mis-ordered snapshot) must not be read as "no delivery".
        assertEquals(1, consumableDeliveryEvidence(true, injectionCallsBefore = 7, injectionCallsAfter = 3))
        // The normal direction is unchanged: one new call is one unconfirmed delivery, none is none.
        assertEquals(1, consumableDeliveryEvidence(true, 3, 4))
        assertEquals(0, consumableDeliveryEvidence(true, 4, 4))
    }
}
