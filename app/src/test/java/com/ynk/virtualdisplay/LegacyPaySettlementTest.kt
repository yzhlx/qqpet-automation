package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

/** Actual files/journals/PNG decoding; matcher scores are injected at the documented image seam. */
class LegacyPaySettlementTest {
    private class Fixture(val second:Boolean=false) {
        val id=if(second) "ce43ca0a-339d-4b18-98a4-6f4c7b28fde3" else "35ac319f-b99d-4d40-a8e7-8053332a470e"
        val inputAt=if(second) 1792642073044L else 1792631939819L
        val root=File("../.build-tmp/legacy-pay-settlement/${UUID.randomUUID()}").absoluteFile.apply {mkdirs()}
        val run=File(root,if(second) "1792642047619" else "1792631909533").apply {mkdirs()}
        val record=File(run,"run-record.json")
        val journal=File(root,"$id.jsonl")
        val before=File(run,"round-1-exchange-before-pay.png")
        val after=File(run,"round-1-exchange-after-pay.png")
        val checkpoint=File(root,"checkpoint.json").apply {writeText("{\"sessionId\":\"$id\",\"actionPending\":true}")}
        val frames=LegacyPayFrameReader {file ->
            val image=ImageIO.read(file) ?: error("BAD_PNG")
            val code=image.getRGB(0,0) and 255
            when(code) {
                1 -> LegacyPayFrame(image.width,image.height,1.0,0.2,0.8,0.8)
                2 -> LegacyPayFrame(image.width,image.height,0.2,1.0,0.8,0.8)
                3 -> LegacyPayFrame(image.width,image.height,0.2,0.2,1.0,0.8)
                4 -> LegacyPayFrame(image.width,image.height,0.2,0.2,0.8,1.0)
                else -> LegacyPayFrame(image.width,image.height,1.0,1.0,0.8,0.8)
            }
        }
        init {
            record.writeText(EventJson.encode(mapOf("sessionId" to id,"terminalState" to "FAILED","courseStarts" to 0,
                "startedAt" to inputAt-10000,"runtimeMs" to 20000)))
            journal.writeText(event(1,"INPUT_SENT",inputAt,mapOf("action" to "TAP","x" to 360.0,"y" to 1425.0))+"\n"+
                event(2,"SESSION_STATE",inputAt+2000,mapOf("state" to "STOPPED"))+"\n")
            image(before,if(second) 2 else 1);image(after,if(second) 4 else 3)
            check(before.setLastModified(inputAt-1000));check(after.setLastModified(inputAt+1000))
        }
        fun event(sequence:Long,kind:String,at:Long,payload:Map<String,Any?>,session:String=id)=EventJson.encode(mapOf(
            "sessionId" to session,"sequence" to sequence,"kind" to kind,"source" to "APP_AUTOMATION","wallTimeMs" to at,"payload" to payload))
        fun image(file:File,code:Int,width:Int=720) {
            val image=BufferedImage(width,1600,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,code)
            check(ImageIO.write(image,"png",file))
        }
        fun read()=LegacyPaySettlementReader(frames).read(id,run,journal)
    }
    private fun refused(block:()->Unit) {try {block();fail("receipt must be refused")} catch(_:IllegalStateException) {}}
    @Test fun onlyBothExactSessionsCanReadLastPaymentAndMatchingFoodOrBathImages() {
        listOf(false,true).forEach {second ->
            val f=Fixture(second);val receipt=f.read()
            assertEquals(f.id,receipt.sessionId);assertEquals(f.inputAt,receipt.lastInputWall)
            assertEquals(1L,receipt.lastInputSequence);assertTrue(receipt.evidence().contains(f.after.canonicalPath))
        }
    }
    @Test fun wrongSessionInJournalOrTerminalRecordIsRefused() {
        val journalWrong=Fixture();journalWrong.journal.writeText(journalWrong.event(1,"INPUT_SENT",journalWrong.inputAt,
            mapOf("action" to "TAP","x" to 360,"y" to 1425),"another-session")+"\n")
        refused {journalWrong.read()}
        val recordWrong=Fixture();recordWrong.record.writeText(recordWrong.record.readText().replace(recordWrong.id,"other"))
        refused {recordWrong.read()}
    }
    @Test fun actualLastInputMustBeTheExactPaymentNotAnEarlierPayment() {
        val f=Fixture();f.journal.appendText(f.event(3,"INPUT_SENT",f.inputAt+3000,
            mapOf("action" to "TAP","x" to 360,"y" to 1380))+"\n")
        refused {f.read()}
        val receipt=Fixture().read()
        refused {LegacyPaySettlementPolicy.requireValid(receipt.copy(lastInputAction="CONTINUOUS_DRAG"))}
        refused {LegacyPaySettlementPolicy.requireValid(receipt.copy(lastInputWall=receipt.lastInputWall+1))}
    }
    @Test fun beforeMustBeAnUnambiguousFoodOrSoapDialogAndAfterMustMatchIt() {
        val wrong=Fixture();wrong.image(wrong.after,4);wrong.after.setLastModified(wrong.inputAt+1000)
        refused {wrong.read()}
        val ambiguous=Fixture();ambiguous.image(ambiguous.before,9);ambiguous.before.setLastModified(ambiguous.inputAt-1000)
        refused {ambiguous.read()}
        val stillOpen=Fixture();stillOpen.image(stillOpen.after,1);stillOpen.after.setLastModified(stillOpen.inputAt+1000)
        refused {stillOpen.read()}
    }
    @Test fun staleAfterImageOrAfterRunImageCannotProveTheLastInput() {
        val stale=Fixture();stale.after.setLastModified(stale.inputAt-2001);refused {stale.read()}
        val late=Fixture();late.after.setLastModified(late.inputAt+11001);refused {late.read()}
        val beforeLate=Fixture();beforeLate.before.setLastModified(beforeLate.inputAt+1001);refused {beforeLate.read()}
    }
    @Test fun missingAfterReceiptAndWrongDimensionsRefuse() {
        val missing=Fixture();val confirmed=missing.after.canonicalFile
        check(confirmed.parentFile==missing.run.canonicalFile);check(confirmed.delete())
        refused {missing.read()}
        val size=Fixture();size.image(size.after,3,719);size.after.setLastModified(size.inputAt+1000)
        refused {size.read()}
    }
    @Test fun truncatedJournalDuplicateKeysWrongSequenceAndNoStopAreRejected() {
        val truncated=Fixture();truncated.journal.writeText(truncated.journal.readText().trimEnd());refused {truncated.read()}
        val duplicate=Fixture();duplicate.journal.writeText(duplicate.journal.readText().replaceFirst("{","{\"sessionId\":\"fake\","));refused {duplicate.read()}
        val sequence=Fixture();sequence.journal.writeText(sequence.journal.readText().replace("\"sequence\":2","\"sequence\":3"));refused {sequence.read()}
        val noStop=Fixture();noStop.journal.writeText(noStop.journal.readLines().first()+"\n");refused {noStop.read()}
    }
    @Test fun independentSettlementAllowsStoppedReconciliationWithoutChangingPendingCheckpoint() {
        val f=Fixture();val cpBytes=f.checkpoint.readBytes();val receipt=f.read()
        val attempt=BudgetAttempt(AttemptId(f.id,"care",1).value,"2026-10-22",0,ReserveStatus.UNCONFIRMED_RETAINED,"original-unknown")
        val budget=File(f.root,"budget.bin");val disk=PersistentBudgetStore(budget)
        disk.write(BudgetLedgerState("2026-10-22",100,mapOf(attempt.attemptId to attempt)))
        val proof=LegacyCareStoppedProof(attempt.attemptId,f.id,true,f.id,"FAILED","care",0,
            f.checkpoint.absolutePath,f.record.absolutePath,receipt)
        val baseline=CareRoomBaseline(5,19000,19500,true,true,true,
            ObservedValue.Known(15,19000,1.0,"new1",ObservationSource.DIGIT_RECOGNITION),
            ObservedValue.Known(15,19500,1.0,"new2",ObservationSource.DIGIT_RECOGNITION),"new1","new2")
        PlanBudgetLedger(disk).reconcileStopped(listOf(proof),baseline,true,20000)
        assertArrayEquals(cpBytes,f.checkpoint.readBytes())
        val saved=PersistentBudgetStore(budget).read().attempts.getValue(attempt.attemptId)
        assertEquals(ReserveStatus.RECONCILED_STOPPED,saved.status);assertEquals(0,saved.minutes)
        assertTrue(saved.reason!!.contains("checkpoint.actionPending=true"))
        assertTrue(saved.reason!!.contains("LAST_PAY_SETTLED"));assertTrue(saved.reason!!.startsWith("original-unknown"))
        assertFalse(PlanBudgetLedger(disk).reserve(attempt.attemptId,0).newlyCharged)
    }
    @Test fun otherPendingSessionsCannotBorrowOneOfTheTwoValidReceipts() {
        val receipt=Fixture().read()
        refused {LegacyPaySettlementPolicy.requireValid(receipt.copy(sessionId="43bced43-485f-4cf7-b815-915880f1254c"))}
        refused {LegacyPaySettlementPolicy.requireValid(receipt.copy(recordSessionId="another-session"))}
        refused {LegacyPaySettlementPolicy.requireValid(receipt.copy(runDirectory="other-run"))}
    }
}
