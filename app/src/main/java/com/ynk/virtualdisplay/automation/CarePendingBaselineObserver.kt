package com.ynk.virtualdisplay.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File

/** Reads old evidence only. A missing checkpoint or terminal record is never inferred from the UI. */
class LegacyCareStoppedEvidenceReader(private val filesDir:File,private val externalFilesDir:File,
    private val eventsDirectory:File,private val settlementReader:LegacyPaySettlementReader) {
    fun read(attempts:Collection<BudgetAttempt>):List<LegacyCareStoppedProof> = attempts
        .filter(CarePendingReconciliation::unresolved).map { attempt ->
            val id=CarePendingReconciliation.sessionId(attempt)
            val checkpoint=File(filesDir,"pet-checkpoints/$id.json")
            check(checkpoint.isFile && checkpoint.length()<=1_000_000) {"LEGACY_CHECKPOINT_MISSING:$id"}
            val cp=JSONObject(checkpoint.readText(Charsets.UTF_8))
            check(cp.getString("sessionId")==id) {"LEGACY_CHECKPOINT_IDENTITY:$id"}
            val pending=cp.getBoolean("actionPending")
            check(!pending || LegacyPaySettlementPolicy.isEligible(id)) {"LEGACY_ACTION_PENDING:$id"}
            val sessions=File(externalFilesDir,"pet_mvp_session").listFiles()
                ?: error("LEGACY_TERMINAL_DIRECTORY_UNREADABLE:$id")
            val matches=sessions.filter {it.isDirectory}.mapNotNull {directory ->
                val record=File(directory,"run-record.json")
                if(!record.isFile) null else {
                    check(record.length()<=1_000_000) {"LEGACY_TERMINAL_TOO_LARGE"}
                    val json=JSONObject(record.readText(Charsets.UTF_8))
                    if(json.getString("sessionId")==id) record to json else null
                }
            }
            check(matches.size==1) {"LEGACY_TERMINAL_MISSING_OR_AMBIGUOUS:$id"}
            val (record,json)=matches.single()
            val verdict=File(record.parentFile,"verdict.json")
            check(verdict.isFile && verdict.length()<=1_000_000) {"LEGACY_VERDICT_MISSING:$id"}
            val mode=JSONObject(verdict.readText(Charsets.UTF_8)).getString("mode")
            LegacyCareStoppedProof(attempt.attemptId,cp.getString("sessionId"),cp.getBoolean("actionPending"),
                json.getString("sessionId"),json.getString("terminalState"),mode,json.getInt("courseStarts"),
                checkpoint.absolutePath,record.absolutePath+"|"+verdict.absolutePath,
                if(pending) settlementReader.read(id,record.parentFile,File(eventsDirectory,"$id.jsonl")) else null)
        }
}

class AndroidLegacyPayFrameReader(context:Context):LegacyPayFrameReader {
    private val matcher=TemplateMatcher(context)
    private val food=PetProfile.Probe("legacy-food-title",Rect(278,848,432,902),"pet_exchange/dialog_food_title.png")
    private val soap=PetProfile.Probe("legacy-soap-title",Rect(268,846,512,906),"pet_exchange/dialog_soap_title.png")
    override fun read(file:File):LegacyPayFrame {
        val bitmap=android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: error("LEGACY_PAY_IMAGE_DECODE_FAILED")
        try {
            check(bitmap.width==720 && bitmap.height==1600) {"LEGACY_PAY_IMAGE_SIZE_INVALID"}
            fun score(probe:PetProfile.Probe)=matcher.match(bitmap,probe,0.97).score
            return LegacyPayFrame(bitmap.width,bitmap.height,score(food),score(soap),score(PetPageProbes.foodScene),score(PetPageProbes.bath))
        } finally {bitmap.recycle()}
    }
}

/** Capture/OCR only: deliberately has no PetInputController, PetFlowContext or navigation object. */
class CarePendingBaselineObserver(private val context:Context,private val capture:HeadlessDisplayCapture,
    private val displayId:Int,private val evidence:File,private val verify:()->Unit) {
    private val profile=PetProfile.calibrated()
    private val matcher=TemplateMatcher(context)
    private val textReader=MlKitCourseNameReader()
    private val walletReader=DeviceWalletReader(textReader)
    private val overlays=listOf(PetPageProbes.healthPanel,profile.resultProbe,
        PetProfile.Probe("exchange-food",Rect(278,848,432,902),"pet_exchange/dialog_food_title.png"),
        PetProfile.Probe("exchange-soap",Rect(268,846,512,906),"pet_exchange/dialog_soap_title.png"))
    private data class Sample(val at:Long,val wallet:ObservedValue<Int>,val path:String,val pixels:IntArray)
    private suspend fun sample(index:Int):Sample {
        evidence.mkdirs()
        val file=File(evidence,"baseline-$index.png")
        val observationsFile=File(evidence,"baseline-$index-observations.json")
        val observations=JSONObject().put("index",index).put("displayId",displayId)
            .put("frameEvidence",file.absolutePath).put("roomThreshold",0.97).put("overlayRejectThreshold",0.80)
            .put("wallet",JSONObject().put("status","NOT_OBSERVED").put("source","NO_FRAME"))
        fun persist() {observationsFile.writeText(observations.toString(),Charsets.UTF_8)}
        val frame=try {
            currentCoroutineContext().ensureActive();verify()
            capture.awaitFrame(1500)
        } catch(t:Throwable) {
            observations.put("refusalReason",t.message ?: t.javaClass.simpleName).put("frameAvailable",false)
            persist();throw t
        }
        try {
            // Evidence is written before any identity/overlay/freshness refusal. A suspect frame is
            // diagnostic evidence only; it never becomes permission to reconcile or send input.
            file.outputStream().use {check(frame.compress(Bitmap.CompressFormat.PNG,100,it))}
            observations.put("frameAvailable",true).put("width",frame.width).put("height",frame.height)
                .put("capture",capture.telemetry())
            val mono=capture.facts.clock.monotonicMs()
            val age=capture.facts.frames.trustedFrameAgeMs(mono)
            val wall=capture.facts.clock.wallTimeMs()
            val at=age?.let {wall-it} ?: wall
            observations.put("frameReceiptAgeMs",age ?: JSONObject.NULL).put("observedAt",at)
                .put("observedAtSource",if(age==null) "UNKNOWN" else "TRANSPORT_RECEIPT")
            val roomScore=matcher.match(frame,PetPageProbes.room,0.97).score
            observations.put("roomScore",roomScore)
            val overlayScores=JSONObject()
            observations.put("overlayScores",overlayScores)
            overlays.forEach {probe -> overlayScores.put(probe.name,matcher.match(frame,probe,0.80).score)}
            val wallet=if(age==null) ObservedValue.Unknown("FRAME_RECEIPT_UNKNOWN",wall,file.absolutePath)
                else walletReader.read(frame,at,file.absolutePath)
            observations.put("wallet",JSONObject(EventJson.encode(wallet.fields())))
                .put("walletFrameEvidence",file.absolutePath)
            val lines=textReader.read(frame)
            val forbidden=Regex("兑换|正在学习|剩余.*[0-9]|成绩单|确认|确定|取消|获得奖励|去上课|去打工")
            val dialogTextVisible=lines.any {forbidden.containsMatchIn(it.text)}
            val roomExit=RoomExitPolicy.select(lines.map {
                RoomExitPolicy.Label(it.text,it.bounds.left,it.bounds.top,it.bounds.right,it.bounds.bottom)
            },frame.width,frame.height)
            val learningScores=profile.learningProbes.map { matcher.match(frame,it,profile.matchThreshold).score }
            observations.put("roomExitConfirmed",roomExit!=null)
                .put("learningThreshold",profile.matchThreshold)
                .put("learningScores",org.json.JSONArray(learningScores))
            val locked=(context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
            observations.put("dialogTextVisible",dialogTextVisible).put("phoneLocked",locked)
            persist()
            currentCoroutineContext().ensureActive();verify()
            check(!locked) {"PHONE_LOCKED"}
            capture.facts.frames.requireFreshInput(capture.facts.clock.monotonicMs(),1500)
            CareBaselinePagePolicy.requireRoom(roomScore,
                overlays.map {overlayScores.getDouble(it.name)},learningScores,
                roomExit!=null,dialogTextVisible,profile.matchThreshold)
            val pixels=buildList {
                val r=PetPageProbes.room.rect
                for(y in r.top until r.bottom step 8) for(x in r.left until r.right step 8) add(frame.getPixel(x,y))
            }.toIntArray()
            observations.put("sampleAccepted",true);persist()
            return Sample(at,wallet,file.absolutePath,pixels)
        } catch(t:Throwable) {
            observations.put("refusalReason",t.message ?: t.javaClass.simpleName).put("sampleAccepted",false)
            persist();throw t
        } finally {frame.recycle()}
    }
    suspend fun observe():CareRoomBaseline {
        val first=sample(1);delay(250);val second=sample(2)
        val stable=first.pixels.size==second.pixels.size && first.pixels.indices.all {i ->
            val a=first.pixels[i];val b=second.pixels[i]
            listOf(0,8,16).all {shift -> kotlin.math.abs(((a shr shift) and 255)-((b shr shift) and 255))<=12}
        }
        return CareRoomBaseline(displayId,first.at,second.at,stable,true,true,
            first.wallet,second.wallet,first.path,second.path)
    }
}
