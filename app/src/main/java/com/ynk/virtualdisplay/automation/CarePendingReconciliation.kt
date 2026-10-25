package com.ynk.virtualdisplay.automation

/** Evidence that a legacy session stopped. This never claims its last care input succeeded. */
data class LegacyCareStoppedProof(
    val attemptId:String, val checkpointSessionId:String, val actionPending:Boolean,
    val terminalSessionId:String, val terminalState:String, val mode:String,
    val courseStarts:Int, val checkpointEvidence:String, val terminalEvidence:String,
    val paySettlement:LegacyPaySettlementReceipt?=null,
)
data class CareRoomBaseline(
    val displayId:Int, val firstAt:Long, val secondAt:Long, val stable:Boolean,
    val roomVerified:Boolean, val overlaysAbsent:Boolean,
    val firstWallet:ObservedValue<Int>, val secondWallet:ObservedValue<Int>,
    val firstEvidence:String, val secondEvidence:String,
)

/** Narrow migration for exactly the seven known legacy zero-minute CARE intents. */
object CarePendingReconciliation {
    val legacySessionIds:Set<String> = setOf(
        "35ac319f-b99d-4d40-a8e7-8053332a470e", "43bced43-485f-4cf7-b815-915880f1254c",
        "60e4935f-5e50-4ae6-bd1d-2001eded96b4", "af6a82db-5e97-4e7c-85c5-89aadd6ec963",
        "ce43ca0a-339d-4b18-98a4-6f4c7b28fde3", "d8f87152-f91e-4751-b928-8fe5aced5756",
        "e460a1fe-8c07-462b-94cc-f0230ac1b1cc")
    private val allowedIds=legacySessionIds.associateBy {AttemptId(it,"care",1).value}
    fun unresolved(attempt:BudgetAttempt)=attempt.status !in setOf(ReserveStatus.COMPLETED,ReserveStatus.RECONCILED_STOPPED)
    fun sessionId(attempt:BudgetAttempt):String {
        check(attempt.minutes==0 && attempt.status==ReserveStatus.UNCONFIRMED_RETAINED && attempt.day=="2026-10-22") {"LEGACY_CARE_ATTEMPT_NOT_ELIGIBLE"}
        return allowedIds[attempt.attemptId] ?: error("LEGACY_CARE_ATTEMPT_NOT_ALLOWLISTED")
    }
    fun validate(attempts:Collection<BudgetAttempt>, proofs:List<LegacyCareStoppedProof>,
        baseline:CareRoomBaseline, noV2Pending:Boolean, now:Long):Map<String,String> {
        check(noV2Pending) {"V2_PENDING_PREVENTS_RECONCILIATION"}
        val pending=attempts.filter(::unresolved)
        check(pending.isNotEmpty()) {"NO_LEGACY_CARE_PENDING"}
        pending.forEach(::sessionId)
        check(proofs.map {it.attemptId}.distinct().size==proofs.size &&
            proofs.map {it.attemptId}.toSet()==pending.map {it.attemptId}.toSet()) {"LEGACY_CARE_PROOFS_INCOMPLETE"}
        check(baseline.displayId>0 && baseline.firstAt>=0 && baseline.secondAt>baseline.firstAt &&
            baseline.secondAt-baseline.firstAt<=5000 && now>=baseline.secondAt && now-baseline.secondAt<=3000 &&
            baseline.stable && baseline.roomVerified && baseline.overlaysAbsent &&
            baseline.firstEvidence.isNotBlank() && baseline.secondEvidence.isNotBlank() &&
            baseline.firstEvidence!=baseline.secondEvidence) {"ROOM_BASELINE_UNCONFIRMED"}
        fun wallet(value:ObservedValue<Int>,at:Long,evidence:String):Int {
            val known=value.at(now,3000) as? ObservedValue.Known ?: error("BASELINE_WALLET_UNCONFIRMED")
            check(known.observedAt==at && known.evidence==evidence && known.value>=0 &&
                known.confidence>=0.95 && known.source==ObservationSource.DIGIT_RECOGNITION) {"BASELINE_WALLET_NOT_SAME_FRAME"}
            return known.value
        }
        check(wallet(baseline.firstWallet,baseline.firstAt,baseline.firstEvidence)==
            wallet(baseline.secondWallet,baseline.secondAt,baseline.secondEvidence)) {"BASELINE_WALLET_CHANGED"}
        return pending.associate { attempt ->
            val id=sessionId(attempt); val proof=proofs.single {it.attemptId==attempt.attemptId}
            if(proof.actionPending) {
                val settlement=proof.paySettlement ?: error("LEGACY_ACTION_PENDING_WITHOUT_SETTLEMENT:$id")
                check(settlement.sessionId==id) {"LEGACY_SETTLEMENT_SESSION_MISMATCH"}
                LegacyPaySettlementPolicy.requireValid(settlement)
            }
            check(proof.checkpointSessionId==id && proof.terminalSessionId==id &&
                proof.terminalState in setOf("STOPPED","FAILED","COMPLETED") && proof.mode=="care" &&
                proof.courseStarts==0 && proof.checkpointEvidence.isNotBlank() && proof.terminalEvidence.isNotBlank()) {
                "LEGACY_CARE_STOP_NOT_PROVEN:$id"
            }
            attempt.attemptId to ((attempt.reason ?: "") + "\nRECONCILED_STOPPED;OUTCOME_REMAINS_UNKNOWN;at=$now;"+
                "checkpoint=${proof.checkpointEvidence};terminal=${proof.terminalEvidence};"+
                "baseline=${baseline.firstEvidence}|${baseline.secondEvidence};checkpoint.actionPending=${proof.actionPending};"+
                (proof.paySettlement?.evidence() ?: "NO_PENDING_INPUT"))
        }
    }
}
