package com.ynk.virtualdisplay.automation

import java.io.File

/** Scores are supplied by the actual Android matcher, or the explicit image seam in JVM tests. */
data class LegacyPayFrame(val width:Int,val height:Int,val foodTitle:Double,val soapTitle:Double,
    val foodScene:Double,val bathScene:Double)
fun interface LegacyPayFrameReader { fun read(file:File):LegacyPayFrame }
data class LegacyPaySettlementReceipt(
    val sessionId:String,val journalSessionId:String,val recordSessionId:String,
    val lastInputAction:String,val lastInputX:Double,val lastInputY:Double,
    val lastInputWall:Long,val lastInputSequence:Long,val stoppedWall:Long,
    val startedAt:Long,val endedAt:Long,val runDirectory:String,
    val beforeModifiedAt:Long,val afterModifiedAt:Long,
    val before:LegacyPayFrame,val after:LegacyPayFrame,
    val journalEvidence:String,val beforeEvidence:String,val afterEvidence:String,
) {
    fun evidence():String="LAST_PAY_SETTLED:session=$sessionId;sequence=$lastInputSequence;wall=$lastInputWall;"+
        "beforeMtime=$beforeModifiedAt;afterMtime=$afterModifiedAt;runStart=$startedAt;runEnd=$endedAt;stoppedWall=$stoppedWall;"+
        "beforeFoodTitle=${before.foodTitle};beforeSoapTitle=${before.soapTitle};afterFoodScene=${after.foodScene};afterBathScene=${after.bathScene};"+
        "afterFoodTitle=${after.foodTitle};afterSoapTitle=${after.soapTitle};journal=$journalEvidence;before=$beforeEvidence;after=$afterEvidence"
}

object LegacyPaySettlementPolicy {
    private val permitted=mapOf(
        "35ac319f-b99d-4d40-a8e7-8053332a470e" to ("1792631909533" to 1792631939819L),
        "ce43ca0a-339d-4b18-98a4-6f4c7b28fde3" to ("1792642047619" to 1792642073044L))
    fun isEligible(sessionId:String)=sessionId in permitted
    fun requireValid(r:LegacyPaySettlementReceipt) {
        val allowed=permitted[r.sessionId] ?: error("LEGACY_PAY_SESSION_NOT_ALLOWLISTED")
        check(r.journalSessionId==r.sessionId && r.recordSessionId==r.sessionId &&
            r.runDirectory==allowed.first && r.lastInputWall==allowed.second) {"LEGACY_PAY_IDENTITY_MISMATCH"}
        check(r.lastInputAction=="TAP" && r.lastInputX==360.0 && r.lastInputY==1425.0 && r.lastInputSequence>0) {"LEGACY_LAST_INPUT_NOT_PAYMENT"}
        check(r.startedAt>=0 && r.endedAt>=r.startedAt && r.lastInputWall in r.startedAt..r.endedAt &&
            r.stoppedWall>=r.lastInputWall && r.stoppedWall<=r.endedAt+1000) {"LEGACY_PAY_RUN_TIME_MISMATCH"}
        check(r.beforeModifiedAt>=r.startedAt-1000 && r.beforeModifiedAt<=r.lastInputWall+1000 &&
            r.afterModifiedAt>=r.lastInputWall-1000 && r.afterModifiedAt<=r.endedAt+1000 &&
            r.afterModifiedAt>=r.beforeModifiedAt) {"LEGACY_PAY_IMAGE_TIME_MISMATCH"}
        check(listOf(r.before,r.after).all {it.width==720 && it.height==1600}) {"LEGACY_PAY_IMAGE_SIZE_INVALID"}
        val food=r.before.foodTitle>=0.95 && r.before.soapTitle<0.90
        val soap=r.before.soapTitle>=0.95 && r.before.foodTitle<0.90
        check(food.xor(soap)) {"LEGACY_PAY_BEFORE_DIALOG_AMBIGUOUS"}
        check(r.after.foodTitle<0.90 && r.after.soapTitle<0.90 &&
            (if(food) r.after.foodScene>=0.97 && r.after.bathScene<0.97
             else r.after.bathScene>=0.97 && r.after.foodScene<0.97)) {"LEGACY_PAY_AFTER_NOT_SETTLED"}
        check(listOf(r.journalEvidence,r.beforeEvidence,r.afterEvidence).all {it.isNotBlank()} &&
            r.beforeEvidence!=r.afterEvidence) {"LEGACY_PAY_EVIDENCE_MISSING"}
    }
}

/** Reads the complete journal and actual per-run image files; never updates a checkpoint. */
class LegacyPaySettlementReader(private val frames:LegacyPayFrameReader) {
    fun read(sessionId:String,runDirectory:File,journal:File):LegacyPaySettlementReceipt {
        check(LegacyPaySettlementPolicy.isEligible(sessionId)) {"LEGACY_PAY_SESSION_NOT_ALLOWLISTED"}
        val recordFile=File(runDirectory,"run-record.json")
        val record=LegacySettlementJson.objectAt(readBounded(recordFile,1_000_000))
        check(record.string("sessionId")==sessionId && record.string("terminalState") in setOf("STOPPED","FAILED","COMPLETED") &&
            record.long("courseStarts")==0L) {"LEGACY_PAY_TERMINAL_MISMATCH"}
        val started=record.long("startedAt");val ended=Math.addExact(started,record.long("runtimeMs"))
        val text=readBounded(journal,50_000_000)
        check(text.endsWith("\n")) {"LEGACY_PAY_JOURNAL_INCOMPLETE"}
        var lastInput:Map<String,Any?>?=null
        var stoppedWall:Long?=null
        var sequence=0L
        text.lineSequence().filter {it.isNotBlank()}.forEach {line ->
            val event=LegacySettlementJson.objectAt(line)
            check(event.string("sessionId")==sessionId && event.long("sequence")==++sequence) {"LEGACY_PAY_JOURNAL_IDENTITY_OR_SEQUENCE"}
            when(event.string("kind")) {
                "INPUT_SENT" -> {
                    check(event.string("source")=="APP_AUTOMATION") {"LEGACY_PAY_INPUT_SOURCE_INVALID"}
                    lastInput=event
                }
                "SESSION_STATE" -> if(event.obj("payload").string("state")=="STOPPED") stoppedWall=event.long("wallTimeMs")
            }
        }
        val last=lastInput ?: error("LEGACY_PAY_LAST_INPUT_MISSING")
        val payload=last.obj("payload")
        val canonicalDirectory=runDirectory.canonicalFile
        val beforeFile=File(canonicalDirectory,"round-1-exchange-before-pay.png")
        val afterFile=File(canonicalDirectory,"round-1-exchange-after-pay.png")
        listOf(beforeFile,afterFile).forEach {file ->
            check(file.isFile && file.length() in 1..10_000_000 && file.canonicalFile.parentFile==runDirectory.canonicalFile &&
                file.canonicalFile==file.absoluteFile) {"LEGACY_PAY_IMAGE_MISSING_OR_WRONG_DIRECTORY"}
        }
        // Metadata is checked again after decoding so changing files during verification fails closed.
        val beforeMtime=beforeFile.lastModified();val afterMtime=afterFile.lastModified()
        val before=frames.read(beforeFile);val after=frames.read(afterFile)
        check(beforeFile.lastModified()==beforeMtime && afterFile.lastModified()==afterMtime) {"LEGACY_PAY_IMAGE_CHANGED"}
        val result=LegacyPaySettlementReceipt(sessionId,sessionId,record.string("sessionId"),
            payload.string("action"),payload.number("x"),payload.number("y"),last.long("wallTimeMs"),
            last.long("sequence"),stoppedWall ?: error("LEGACY_PAY_STOP_EVENT_MISSING"),started,ended,runDirectory.name,
            beforeMtime,afterMtime,before,after,journal.absolutePath,beforeFile.absolutePath,afterFile.absolutePath)
        LegacyPaySettlementPolicy.requireValid(result)
        return result
    }
    private fun readBounded(file:File,max:Long):String {
        check(file.isFile && file.length() in 1..max) {"LEGACY_PAY_FILE_MISSING_OR_TOO_LARGE:${file.name}"}
        val bytes=file.readBytes();check(bytes.size<=max)
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}

/** Strict small JSON reader for durable receipts; no Android JSON stubs in file tests. */
internal object LegacySettlementJson {
    data class Number(val text:String)
    fun objectAt(text:String):Map<String,Any?> {
        val reader=Reader(text);val value=reader.value(0);reader.space();check(reader.at==text.length) {"LEGACY_JSON_TRAILING"}
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String,Any?> ?: error("LEGACY_JSON_OBJECT_REQUIRED")
    }
    class Reader(val text:String) {
        var at=0
        fun space() {while(at<text.length && text[at] in " \r\n\t") at++}
        fun take(c:Char):Boolean {space();if(at<text.length && text[at]==c){at++;return true};return false}
        fun expect(c:Char) {check(take(c)) {"LEGACY_JSON_EXPECTED:$c"}}
        fun value(depth:Int):Any? {
            check(depth<=40) {"LEGACY_JSON_DEPTH"};space();check(at<text.length)
            return when(text[at]) {
                '{' -> {at++;val out=linkedMapOf<String,Any?>();if(!take('}')) while(true){val key=string();expect(':');check(!out.containsKey(key));out[key]=value(depth+1);if(take('}'))break;expect(',')};out}
                '[' -> {at++;val out=mutableListOf<Any?>();if(!take(']')) while(true){out+=value(depth+1);if(take(']'))break;expect(',')};out}
                '"' -> string()
                't' -> literal("true",true)
                'f' -> literal("false",false)
                'n' -> literal("null",null)
                else -> {val match=Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").find(text,at);check(match!=null && match.range.first==at);at=match.range.last+1;Number(match.value)}
            }
        }
        private fun literal(word:String,value:Any?):Any? {check(text.startsWith(word,at));at+=word.length;return value}
        private fun string():String {
            expect('"');val out=StringBuilder()
            while(at<text.length) {
                val c=text[at++];if(c=='"')return out.toString()
                check(c.code>=32)
                if(c!='\\') out.append(c) else {
                    check(at<text.length)
                    when(val e=text[at++]) {
                        '"','\\','/' -> out.append(e)
                        'b' -> out.append('\b');'f' -> out.append('\u000c');'n' -> out.append('\n');'r' -> out.append('\r');'t' -> out.append('\t')
                        'u' -> {check(at+4<=text.length);out.append(text.substring(at,at+4).toInt(16).toChar());at+=4}
                        else -> error("LEGACY_JSON_ESCAPE")
                    }
                }
            }
            error("LEGACY_JSON_UNTERMINATED")
        }
    }
}
internal fun Map<String,Any?>.string(key:String)=get(key) as? String ?: error("LEGACY_JSON_STRING:$key")
internal fun Map<String,Any?>.long(key:String)=(get(key) as? LegacySettlementJson.Number)?.text?.toLongOrNull() ?: error("LEGACY_JSON_INTEGER:$key")
internal fun Map<String,Any?>.number(key:String)=(get(key) as? LegacySettlementJson.Number)?.text?.toDoubleOrNull()?.takeIf {it.isFinite()} ?: error("LEGACY_JSON_NUMBER:$key")
@Suppress("UNCHECKED_CAST")
internal fun Map<String,Any?>.obj(key:String)=get(key) as? Map<String,Any?> ?: error("LEGACY_JSON_OBJECT:$key")
