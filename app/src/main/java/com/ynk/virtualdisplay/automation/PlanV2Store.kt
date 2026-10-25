package com.ynk.virtualdisplay.automation

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The same schema is used for saved plans and the plan_json start extra. No org.json dependency. */
object PlanV2JsonCodec {
    fun encode(plan: PlanV2): String {
        val root = linkedMapOf<String, Any?>(
            "schemaVersion" to plan.schemaVersion, "planId" to plan.planId, "name" to plan.name,
            "dailyBudgetMinutes" to plan.dailyBudgetMinutes, "executionProfile" to plan.executionProfile.name,
        )
        plan.legacySource?.let { root["legacySource"] = it }
        root["steps"] = plan.steps.map { step ->
            linkedMapOf<String, Any?>("stepId" to step.stepId, "type" to step.type.name).apply {
                when (step) {
                    is WorkStep -> {
                        put("workOptionId", step.workOptionId); put("trigger", step.trigger.name); put("shifts", step.shifts)
                    }
                    is CareStep -> {
                        put("energyThreshold", step.energyThreshold); put("cleanlinessThreshold", step.cleanlinessThreshold)
                    }
                    is StudyStep -> { put("courseId", step.courseId); put("rounds", step.rounds) }
                }
            }
        }
        return Json.write(root).also { require(it.length <= MAX_DOCUMENT_SIZE) { "PLAN_JSON_TOO_LARGE" } }
    }

    /** Throws on invalid or unsupported input. The caller decides whether to display or reject it. */
    fun decode(text: String): PlanV2 {
        require(text.length <= MAX_DOCUMENT_SIZE) { "PLAN_JSON_TOO_LARGE" }
        val root = Json.parse(text).objectValue()
        root.only("schemaVersion", "planId", "name", "dailyBudgetMinutes", "executionProfile", "legacySource", "steps")
        require(root.int("schemaVersion") == 2) { "PLAN_SCHEMA_UNSUPPORTED" }
        val steps = root.list("steps").map { item ->
            val step = item.objectValue()
            val id = step.string("stepId")
            when (enumValueOf<PlanStepType>(step.string("type"))) {
                PlanStepType.WORK -> {
                    step.only("stepId", "type", "workOptionId", "trigger", "shifts")
                    WorkStep(id, step.string("workOptionId"), enumValueOf(step.string("trigger")), step.int("shifts"))
                }
                PlanStepType.CARE -> {
                    step.only("stepId", "type", "energyThreshold", "cleanlinessThreshold")
                    CareStep(id, step.int("energyThreshold"), step.int("cleanlinessThreshold"))
                }
                PlanStepType.STUDY -> {
                    step.only("stepId", "type", "courseId", "rounds")
                    StudyStep(id, step.string("courseId"), step.int("rounds"))
                }
            }
        }
        return PlanV2(2, root.string("planId"), root.string("name"), root.int("dailyBudgetMinutes"), steps,
            enumValueOf(root.string("executionProfile")), root.optionalString("legacySource"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.objectValue(): Map<String, Any?> = this as? Map<String, Any?>
        ?: throw IllegalArgumentException("PLAN_JSON_OBJECT_REQUIRED")
    private fun Map<String, Any?>.only(vararg keys: String) {
        require(this.keys.all { it in keys }) { "PLAN_JSON_UNKNOWN_FIELD" }
    }
    private fun Map<String, Any?>.value(key: String): Any? {
        require(containsKey(key)) { "PLAN_JSON_KEY_MISSING:$key" }; return get(key)
    }
    private fun Map<String, Any?>.string(key: String): String = value(key) as? String
        ?: throw IllegalArgumentException("PLAN_JSON_STRING_REQUIRED:$key")
    private fun Map<String, Any?>.optionalString(key: String): String? = get(key)?.let {
        it as? String ?: throw IllegalArgumentException("PLAN_JSON_STRING_REQUIRED:$key")
    }
    private fun Map<String, Any?>.int(key: String): Int {
        val number = value(key) as? Long ?: throw IllegalArgumentException("PLAN_JSON_INTEGER_REQUIRED:$key")
        require(number in Int.MIN_VALUE..Int.MAX_VALUE) { "PLAN_JSON_INTEGER_RANGE:$key" }
        return number.toInt()
    }
    private fun Map<String, Any?>.list(key: String): List<*> = value(key) as? List<*>
        ?: throw IllegalArgumentException("PLAN_JSON_ARRAY_REQUIRED:$key")

    private const val MAX_DOCUMENT_SIZE = 1_048_576

    private object Json {
        fun write(value: Any?): String = when (value) {
            null -> "null"
            is Boolean, is Int, is Long -> value.toString()
            is String -> buildString {
                append('"')
                for (c in value) when (c) {
                    '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
                    '\r' -> append("\\r"); '\t' -> append("\\t")
                    else -> if (c.code < 32 || c.isSurrogate()) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
                }
                append('"')
            }
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { write(it.key as String) + ":" + write(it.value) }
            is List<*> -> value.joinToString(",", "[", "]") { write(it) }
            else -> throw IllegalArgumentException("PLAN_JSON_UNSUPPORTED_VALUE")
        }
        fun parse(text: String): Any? = Parser(text).parse()
        private class Parser(private val text: String) {
            private var pos = 0
            private fun fail(): Nothing = throw IllegalArgumentException("PLAN_JSON_INVALID_AT:$pos")
            private fun whitespace() { while (pos < text.length && text[pos] in " \r\n\t") pos++ }
            private fun take(char: Char): Boolean {
                whitespace()
                return if (pos < text.length && text[pos] == char) { pos++; true } else false
            }
            fun parse(): Any? { val result = value(0); whitespace(); if (pos != text.length) fail(); return result }
            private fun value(depth: Int): Any? {
                if (depth > 32) fail()
                whitespace(); if (pos >= text.length) fail()
                return when (text[pos]) {
                    '{' -> {
                        pos++; val map = linkedMapOf<String, Any?>()
                        if (!take('}')) do {
                            whitespace(); if (pos >= text.length || text[pos] != '"') fail()
                            val key = string(); if (map.containsKey(key) || !take(':')) fail()
                            map[key] = value(depth + 1)
                            if (take('}')) break
                            if (!take(',')) fail()
                        } while (true)
                        map
                    }
                    '[' -> {
                        pos++; val list = mutableListOf<Any?>()
                        if (!take(']')) do {
                            list.add(value(depth + 1)); if (take(']')) break
                            if (!take(',')) fail()
                        } while (true)
                        list
                    }
                    '"' -> string()
                    't' -> literal("true", true)
                    'f' -> literal("false", false)
                    'n' -> literal("null", null)
                    else -> number()
                }
            }
            private fun literal(token: String, result: Any?): Any? {
                if (!text.startsWith(token, pos)) fail(); pos += token.length; return result
            }
            private fun number(): Long {
                val start = pos
                if (pos < text.length && text[pos] == '-') pos++
                if (pos >= text.length || text[pos] !in '0'..'9') fail()
                if (text[pos] == '0') pos++ else while (pos < text.length && text[pos] in '0'..'9') pos++
                return text.substring(start, pos).toLongOrNull() ?: fail()
            }
            private fun string(): String {
                if (text[pos++] != '"') fail()
                return buildString {
                    while (pos < text.length) {
                        val char = text[pos++]
                        if (char == '"') return@buildString
                        if (char.code < 32) fail()
                        if (char != '\\') append(char) else {
                            if (pos >= text.length) fail()
                            when (val escaped = text[pos++]) {
                                '"', '\\', '/' -> append(escaped)
                                'b' -> append('\b'); 'f' -> append('\u000c'); 'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t')
                                'u' -> {
                                    if (pos + 4 > text.length) fail()
                                    val hex = text.substring(pos, pos + 4)
                                    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail()
                                    append(hex.toInt(16).toChar()); pos += 4
                                }
                                else -> fail()
                            }
                        }
                    }
                    fail()
                }
            }
        }
    }
}

/** Invalid input is never rewritten and never converted to a default runnable plan. */
class PlanV2Store private constructor(private val document: PlanDocument) {
    constructor(context: Context) : this(AndroidPlanDocument(File(context.filesDir, FILE_NAME)))
    internal constructor(file: File) : this(JvmPlanDocument(file.absoluteFile))

    fun load(): PlanV2? = synchronized(lock) {
        try {
            val bytes = document.read() ?: return@synchronized null
            require(bytes.size <= MAX_FILE_BYTES) { "PLAN_FILE_TOO_LARGE" }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            PlanV2JsonCodec.decode(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        } catch (_: Exception) { null }
    }

    fun save(plan: PlanV2) {
        val bytes = PlanV2JsonCodec.encode(plan).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "PLAN_FILE_TOO_LARGE" }
        synchronized(lock) { document.write(bytes) }
    }

    companion object {
        const val FILE_NAME = "plan-v2.json"
        private const val MAX_FILE_BYTES = 1_048_576
        private val lock = Any()
    }
}

private interface PlanDocument {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

/** The production adapter uses Android's AtomicFile for commits; read does not repair invalid JSON. */
private class AndroidPlanDocument(private val file: File) : PlanDocument {
    private val atomic = AtomicFile(file)
    override fun read(): ByteArray? = if (file.exists()) file.readBytes() else null
    override fun write(bytes: ByteArray) {
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}

/** Real-file JVM adapter, sharing the codec and store behavior without Android's no-op test stub. */
private class JvmPlanDocument(private val file: File) : PlanDocument {
    override fun read(): ByteArray? = if (file.exists()) file.readBytes() else null
    override fun write(bytes: ByteArray) {
        val parent = file.parentFile ?: error("PLAN_PARENT_MISSING")
        check(parent.isDirectory || parent.mkdirs()) { "PLAN_DIRECTORY_UNAVAILABLE" }
        val pending = File(parent, file.name + ".pending")
        check(pending.parentFile.canonicalFile == parent.canonicalFile) { "PLAN_PENDING_PATH_INVALID" }
        FileOutputStream(pending).use { stream -> stream.write(bytes); stream.fd.sync() }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
