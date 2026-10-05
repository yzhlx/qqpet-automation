package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors

class ObservationFactsTest {
    private fun known(v: Int, source: ObservationSource = ObservationSource.DIGIT_RECOGNITION) =
        ObservedValue.Known(v,1000,1.0,"fixture",source)
    private fun snapshot(e: ObservedValue<Int> = known(94)) = HealthObservations(e,known(96),known(95),
        known(93,ObservationSource.CONSERVATIVE_BAR_ESTIMATE),known(95,ObservationSource.CONSERVATIVE_BAR_ESTIMATE),
        known(94,ObservationSource.CONSERVATIVE_BAR_ESTIMATE),true,"fixture")
    private fun rejects(block: () -> Unit) {assertTrue(runCatching(block).isFailure)}

    @Test fun knownHealthEntersSafetyDecisionWithConservativeBoundary() {
        val value=snapshot().current(1100)
        assertEquals(93,value.energy);assertTrue(value.ready)
        val borderline=snapshot(known(89)).current(1100)
        assertFalse(borderline.ready)
    }
    @Test fun unknownNeverBecomesZero() {
        val unknown=ObservedValue.Unknown("AMBIGUOUS_DIGIT",1000,"fixture")
        assertEquals("UNKNOWN",unknown.fields()["status"])
        rejects {snapshot(unknown).current(1100)}
    }
    @Test fun staleAndReversedClockCannotAuthorize() {
        assertTrue(known(94).at(61001,60000) is ObservedValue.Stale)
        rejects {snapshot().current(61001,60000)}
        rejects {snapshot().current(999)}
    }
    @Test fun unsupportedDiffersFromAttemptedUnknown() {
        val unsupported=ObservedValue.Unsupported("NO_READER")
        val unknown=ObservedValue.Unknown("READ_FAILED",1000,"frame")
        assertNotEquals(unsupported.fields()["status"],unknown.fields()["status"])
        rejects {snapshot(unsupported).current(1100)}
    }
    private val templates get() = HealthDigitReader.parse(javaClass.getResource("/digits.txt")!!.readText())
    private val pixels get() = javaClass.getResource("/67.txt")!!.readText().trim()
    @Test fun fixedDigitsHaveKnownSourceAndConfidence() {
        val p=pixels
        val value=HealthDigitReader(templates).read(52,31,{x,y->p[y*52+x]=='1'},1000,"67.png")
        assertTrue(value is ObservedValue.Known)
        value as ObservedValue.Known
        assertEquals(67,value.value);assertEquals(1.0,value.confidence,0.0)
        assertEquals(ObservationSource.DIGIT_RECOGNITION,value.source)
    }
    @Test fun lowConfidenceReturnsUnknown() {
        val p=pixels
        val value=HealthDigitReader(templates).read(52,31,{x,y->p[y*52+x]=='1' && (y !in 10..18)},1000,"damaged")
        assertTrue(value is ObservedValue.Unknown)
        assertTrue((value as ObservedValue.Unknown).reason.startsWith("LOW_DIGIT_CONFIDENCE"))
    }
    @Test fun confusingSixEightCannotForceGuess() {
        val p=pixels
        // Inject a conflicting label into the measured bank: even a perfect match must abstain.
        val conflict=templates+templates.filter {it.digit==6}.map {HealthDigitReader.DigitTemplate(8,it.mask)}
        val value=HealthDigitReader(conflict).read(52,31,{x,y->p[y*52+x]=='1'},1000,"conflict")
        assertEquals("AMBIGUOUS_DIGIT",(value as ObservedValue.Unknown).reason)
    }
    @Test fun blankAndUnexpectedRoiAreUnknown() {
        assertTrue(HealthDigitReader(templates).read(52,31,{_,_->false},1000,"empty") is ObservedValue.Unknown)
        assertTrue(HealthDigitReader(templates).read(53,31,{_,_->true},1000,"size") is ObservedValue.Unknown)
    }
    @Test fun livenessChecksNormalBlackSizeDecodeAndDisplay() {
        fun inspect(w:Int=720, h:Int=1600, id:Int=4, decoded:Boolean=true, available:Boolean=true, color:Int=0xffffff) =
            FrameLiveness.inspect(id,w,h,720,1600,available,decoded) {_,_->color}
        assertEquals(FrameStatus.VALID,inspect().status)
        assertEquals(FrameStatus.INVALID_BLACK_FRAME,inspect(color=0).status)
        assertEquals(FrameStatus.INVALID_SIZE,inspect(w=1600,h=720).status)
        assertEquals(FrameStatus.INVALID_DECODE,inspect(decoded=false).status)
        assertEquals(FrameStatus.DISPLAY_UNAVAILABLE,inspect(available=false).status)
        assertEquals(FrameStatus.INVALID_DISPLAY_ID,inspect(id=0).status)
    }
    @Test fun invalidFrameImmediatelyInhibitsAndThreeMissesLatch() {
        val gate=FrameTrustGate(3)
        rejects {gate.requireInputAllowed()}
        gate.observe(FrameLivenessResult(FrameStatus.VALID));gate.requireInputAllowed()
        gate.observe(FrameLivenessResult(FrameStatus.INVALID_BLACK_FRAME));rejects {gate.requireInputAllowed()}
        assertFalse(gate.stopped)
        gate.observe(FrameLivenessResult(FrameStatus.VALID));gate.requireInputAllowed()
        repeat(3) {gate.observe(FrameLivenessResult(FrameStatus.CAPTURE_FAILED))}
        assertTrue(gate.stopped)
        gate.observe(FrameLivenessResult(FrameStatus.VALID));rejects {gate.requireInputAllowed()}
    }
    private val clock=object: EventClock {
        override fun wallTimeMs()=1000L
        override fun monotonicMs()=50L
        override val bootId=ObservedValue.Known("boot-fixture",1000L,1.0,"fixture",ObservationSource.SYSTEM)
    }
    @Test fun eventOrderPersistsAcrossReopenAndSourcesStayDistinct() {
        val dir=Files.createTempDirectory("pet-events").toFile()
        try {
            val store=SessionEventStore(dir,"test",clock)
            val first=store.append(EventSource.APP_AUTOMATION,EventKind.INPUT_SENT,mapOf("action" to "tap"))
            val second=SessionEventStore(dir,"test",clock).append(EventSource.DEBUG_ADB,EventKind.INPUT_SENT)
            assertEquals(1L,first.sequence);assertEquals(2L,second.sequence)
            assertNotEquals(first.eventId,second.eventId)
            val lines=store.file.readLines()
            assertTrue(lines[0].contains("\"source\":\"APP_AUTOMATION\""))
            assertTrue(lines[1].contains("\"source\":\"DEBUG_ADB\""))
            assertTrue(lines[0].contains("\"monotonicMs\":50"));assertTrue(lines[0].contains("boot-fixture"))
        } finally {dir.deleteRecursively()}
    }
    @Test fun concurrentEventAppendIsOrderedAndDurable() {
        val dir=Files.createTempDirectory("pet-events").toFile();val executor=Executors.newFixedThreadPool(3)
        try {
            val store=SessionEventStore(dir,"test",clock)
            val jobs=(1..20).map {executor.submit {store.append(EventSource.USER,EventKind.SESSION_CONFIG)}}
            jobs.forEach {it.get()}
            assertEquals(20,store.file.readLines().size)
            assertEquals(21L,SessionEventStore(dir,"test",clock).append(EventSource.GAME_OBSERVATION,EventKind.FRAME_VALID).sequence)
        } finally {executor.shutdown();dir.deleteRecursively()}
    }
    @Test fun incompleteEventJournalRefusesSilentContinuation() {
        val dir=Files.createTempDirectory("pet-events").toFile()
        try {java.io.File(dir,"test.jsonl").writeText("{\"sequence\":1}");rejects {SessionEventStore(dir,"test",clock)}}
        finally {dir.deleteRecursively()}
    }
}
