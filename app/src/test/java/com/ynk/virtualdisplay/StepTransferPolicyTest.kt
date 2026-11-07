package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PlanStepType
import com.ynk.virtualdisplay.automation.StepState
import com.ynk.virtualdisplay.automation.StepTransferPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STR-01/STR-02：受阻后要不要转移、转到哪一步，只由规则决定。
 */
class StepTransferPolicyTest {

    @Test fun aBlockedActionMovesToTheNextActionWhenTheUserAskedForIt() {
        val decision = StepTransferPolicy.decide(
            enabled = true, stoppedState = StepState.BLOCKED, stoppedType = PlanStepType.WORK,
            remainingTypes = listOf(PlanStepType.CARE, PlanStepType.STUDY), transfersUsed = 0)
        assertTrue(decision is StepTransferPolicy.Decision.Transfer)
        val transfer = decision as StepTransferPolicy.Decision.Transfer
        assertEquals(PlanStepType.CARE, transfer.target)
        assertEquals("TRANSFER_AFTER_WORK", transfer.rule)
    }

    @Test fun aFailedActionAlsoTransfers() {
        val decision = StepTransferPolicy.decide(
            enabled = true, stoppedState = StepState.FAILED, stoppedType = PlanStepType.STUDY,
            remainingTypes = listOf(PlanStepType.ADVENTURE), transfersUsed = 0)
        assertTrue(decision is StepTransferPolicy.Decision.Transfer)
    }

    @Test fun nothingMovesWhenTheUserDidNotOpenTheOption() {
        val decision = StepTransferPolicy.decide(
            enabled = false, stoppedState = StepState.BLOCKED, stoppedType = PlanStepType.WORK,
            remainingTypes = listOf(PlanStepType.CARE), transfersUsed = 0)
        assertEquals(StepTransferPolicy.Decision.Stop("TRANSFER_DISABLED"), decision)
    }

    @Test fun anUnknownOrStoppedOutcomeNeverTransfers() {
        for (state in listOf(StepState.UNCONFIRMED, StepState.CANCELLED, StepState.PENDING, StepState.RUNNING)) {
            val decision = StepTransferPolicy.decide(
                enabled = true, stoppedState = state, stoppedType = PlanStepType.WORK,
                remainingTypes = listOf(PlanStepType.CARE), transfersUsed = 0)
            assertTrue(state.name, decision is StepTransferPolicy.Decision.Stop)
        }
    }

    @Test fun oneRoundTransfersAtMostOnce() {
        val decision = StepTransferPolicy.decide(
            enabled = true, stoppedState = StepState.BLOCKED, stoppedType = PlanStepType.WORK,
            remainingTypes = listOf(PlanStepType.CARE), transfersUsed = 1)
        assertEquals(StepTransferPolicy.Decision.Stop("TRANSFER_ALREADY_USED"), decision)
    }

    @Test fun withoutALaterActionThereIsNothingToMoveTo() {
        val decision = StepTransferPolicy.decide(
            enabled = true, stoppedState = StepState.BLOCKED, stoppedType = PlanStepType.WORK,
            remainingTypes = emptyList(), transfersUsed = 0)
        assertEquals(StepTransferPolicy.Decision.Stop("TRANSFER_NO_TARGET"), decision)
    }
}
