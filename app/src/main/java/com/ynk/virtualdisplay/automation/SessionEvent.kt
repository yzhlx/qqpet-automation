package com.ynk.virtualdisplay.automation

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class EventSource { APP_AUTOMATION, GAME_OBSERVATION, USER, DEBUG_ADB }
enum class EventKind { HEALTH_OBSERVED, HEALTH_UNKNOWN, FRAME_VALID, FRAME_INVALID, INPUT_SENT,
    COURSE_STARTED, LEARNING_CONFIRMED, ERROR, SESSION_CONFIG }
interface EventClock {
    fun wallTimeMs(): Long
    fun monotonicMs(): Long
    val bootId: ObservedValue<String>
}
data class SessionEvent(val eventId: String, val sessionId: String, val sequence: Long,
    val wallTimeMs: Long, val monotonicMs: Long, val bootId: ObservedValue<String>,
    val source: EventSource, val kind: EventKind, val payload: Map<String,Any?>, val evidence: List<String>) {
    fun json(): String = EventJson.encode(linkedMapOf("eventId" to eventId,"sessionId" to sessionId,
        "sequence" to sequence,"wallTimeMs" to wallTimeMs,"monotonicMs" to monotonicMs,
        "bootId" to bootId.fields(),"source" to source.name,"kind" to kind.name,"payload" to payload,"evidence" to evidence))
}

/** One local ordered durable JSONL stream; no queue or product state machine. */
class SessionEventStore(directory: File, val sessionId: String, private val clock: EventClock) {
    val file=File(directory,"$sessionId.jsonl")
    private var sequence=0L
    init {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
        directory.mkdirs()
        if(file.exists()) {
            val text=file.readText()
            check(text.isEmpty() || text.endsWith("\n")) {"EVENT_JOURNAL_INCOMPLETE"}
            val lines=text.lineSequence().filter {it.isNotBlank()}.toList()
            lines.forEachIndexed { index,line ->
                val n=Regex("\"sequence\":(\\d+)").find(line)?.groupValues?.get(1)?.toLong()
                    ?: error("EVENT_JOURNAL_INVALID")
                check(n==index+1L && line.endsWith("}")) {"EVENT_SEQUENCE_INVALID"}
                sequence=n
            }
        }
    }
    @Synchronized fun append(source: EventSource, kind: EventKind, payload: Map<String,Any?> = emptyMap(),
        evidence: List<String> = emptyList()): SessionEvent {
        val event=SessionEvent(UUID.randomUUID().toString(),sessionId,sequence+1,clock.wallTimeMs(),
            clock.monotonicMs(),clock.bootId,source,kind,payload,evidence)
        FileOutputStream(file,true).use { stream ->
            stream.write((event.json()+"\n").toByteArray(Charsets.UTF_8));stream.fd.sync()
        }
        sequence++
        return event
    }
}

object EventJson {
    fun encode(value: Any?): String = when(value) {
        null -> "null"
        is String -> "\""+buildString {value.forEach {c -> when(c) {
            '"' -> append("\\\"");'\\' -> append("\\\\");'\n' -> append("\\n");'\r' -> append("\\r");'\t' -> append("\\t")
            else -> if(c.code<32) append("\\u%04x".format(c.code)) else append(c)
        }}}+"\""
        is Boolean -> value.toString()
        is Number -> {require(value.toDouble().isFinite());value.toString()}
        is Map<*,*> -> value.entries.joinToString(",","{","}") { (k,v) -> require(k is String);encode(k)+":"+encode(v) }
        is Iterable<*> -> value.joinToString(",","[","]") {encode(it)}
        else -> error("UNSUPPORTED_EVENT_VALUE:${value.javaClass.name}")
    }
}
