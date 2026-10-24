package com.ynk.virtualdisplay.automation

/** Strict, dependency-free JSON for the run-store document. All required keys and types are checked.
 * No Android org.json stubs, floating-point timestamp conversions, duplicate keys or schema guessing.
 * Store schema 2 is independent of (and also checks) the embedded plan's schema 2.
 */
internal object PlanRunJsonCodec {
    private fun obj(vararg pairs: Pair<String, Any?>) = linkedMapOf(*pairs)
    private fun plan(p: PlanV2): Map<String, Any?> = obj(
        "schemaVersion" to p.schemaVersion, "planId" to p.planId, "name" to p.name,
        "dailyBudgetMinutes" to p.dailyBudgetMinutes, "executionProfile" to p.executionProfile.name,
        "legacySource" to p.legacySource, "steps" to p.steps.map { s ->
            val fields = obj("stepId" to s.stepId, "type" to s.type.name)
            when (s) {
                is WorkStep -> fields.putAll(obj("workOptionId" to s.workOptionId, "trigger" to s.trigger.name, "shifts" to s.shifts))
                is CareStep -> fields.putAll(obj("energyThreshold" to s.energyThreshold, "cleanlinessThreshold" to s.cleanlinessThreshold))
                is StudyStep -> fields.putAll(obj("courseId" to s.courseId, "rounds" to s.rounds))
            }
            fields
        })
    private fun request(r: RunRequest) = obj("requestId" to r.requestId, "planSnapshot" to plan(r.planSnapshot),
        "deviceSerial" to r.deviceSerial, "displayId" to r.displayId, "origin" to r.origin.name, "createdAtMs" to r.createdAtMs)
    fun sameRequest(a: RunRequest, b: RunRequest) = request(a) == request(b)
    fun encode(record: StoredRun): String {
        val s = record.snapshot
        return Json.write(obj("schemaVersion" to 2, "snapshot" to obj(
            "runId" to s.runId, "request" to request(s.request), "state" to s.state.name,
            "counts" to obj("workShiftsCompleted" to s.counts.workShiftsCompleted,
                "careCompleted" to s.counts.careCompleted, "studyRoundsCompleted" to s.counts.studyRoundsCompleted),
            "startedAtMs" to s.startedAtMs, "endedAtMs" to s.endedAtMs, "stopReason" to s.stopReason,
            "steps" to s.steps.map { e -> obj("runId" to e.runId, "stepId" to e.stepId, "index" to e.index,
                "type" to e.type.name, "state" to e.state.name, "reason" to e.reason,
                "startedAtMs" to e.startedAtMs, "endedAtMs" to e.endedAtMs, "evidenceIds" to e.evidenceIds) }),
            "attempts" to record.attempts.map { a -> obj(
                "runId" to a.attemptId.runId, "stepId" to a.attemptId.stepId, "iteration" to a.attemptId.iteration,
                "durationMinutes" to a.durationMinutes, "state" to a.state.name, "inputPending" to a.inputPending,
                "inputDispatchedAtMs" to a.inputDispatchedAtMs, "startConfirmedAtMs" to a.startConfirmedAtMs,
                "completionConfirmedAtMs" to a.completionConfirmedAtMs,
                "unconfirmedReason" to record.reasons[a.attemptId.value]) }))
    }
    fun decode(text: String): StoredRun {
        val root = Json.parse(text).objectValue()
        require(root.int("schemaVersion") == 2) { "RUN_STORE_SCHEMA_UNSUPPORTED" }
        val s = root.value("snapshot").objectValue()
        val r = s.value("request").objectValue()
        val p = r.value("planSnapshot").objectValue()
        val plan = PlanV2(p.int("schemaVersion"), p.string("planId"), p.string("name"), p.int("dailyBudgetMinutes"),
            p.list("steps").map { value ->
                val step = value.objectValue()
                val id = step.string("stepId")
                when (enumValueOf<PlanStepType>(step.string("type"))) {
                    PlanStepType.WORK -> WorkStep(id, step.string("workOptionId"), enumValueOf(step.string("trigger")), step.int("shifts"))
                    PlanStepType.CARE -> CareStep(id, step.int("energyThreshold"), step.int("cleanlinessThreshold"))
                    PlanStepType.STUDY -> StudyStep(id, step.string("courseId"), step.int("rounds"))
                }
            }, enumValueOf(p.string("executionProfile")), p.nullString("legacySource"))
        val request = RunRequest(r.string("requestId"), plan, r.string("deviceSerial"), r.int("displayId"),
            enumValueOf(r.string("origin")), r.long("createdAtMs"))
        val counts = s.value("counts").objectValue()
        val snapshot = RunSnapshot(s.string("runId"), request, enumValueOf(s.string("state")), s.list("steps").map {
            val e = it.objectValue()
            StepExecution(e.string("runId"), e.string("stepId"), e.int("index"), enumValueOf(e.string("type")),
                enumValueOf(e.string("state")), e.nullString("reason"), e.nullLong("startedAtMs"), e.nullLong("endedAtMs"),
                e.list("evidenceIds").map { id -> id as? String ?: error("RUN_JSON_STRING_REQUIRED") })
        }, RunCounts(counts.int("workShiftsCompleted"), counts.int("careCompleted"), counts.int("studyRoundsCompleted")),
            s.nullLong("startedAtMs"), s.nullLong("endedAtMs"), s.nullString("stopReason"))
        val reasons = linkedMapOf<String, String>()
        val attempts = root.list("attempts").map {
            val a = it.objectValue()
            val id = AttemptId(a.string("runId"), a.string("stepId"), a.int("iteration"))
            a.nullString("unconfirmedReason")?.let { reason ->
                require(reason.isNotBlank()) { "RUN_STORE_REASON_BLANK" }; reasons[id.value] = reason
            }
            ActivityAttempt(id, a.int("durationMinutes"), enumValueOf(a.string("state")), a.value("inputPending") as? Boolean
                ?: error("RUN_JSON_BOOLEAN_REQUIRED"), a.nullLong("inputDispatchedAtMs"), a.nullLong("startConfirmedAtMs"), a.nullLong("completionConfirmedAtMs"))
                .also { attempt -> FileRunStore.validateAttempt(snapshot, attempt) }
        }
        require(attempts.map { it.attemptId }.distinct().size == attempts.size) { "RUN_STORE_DUPLICATE_ATTEMPT" }
        return StoredRun(snapshot, attempts, reasons)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.objectValue(): Map<String, Any?> = this as? Map<String, Any?> ?: error("RUN_JSON_OBJECT_REQUIRED")
    private fun Map<String, Any?>.value(k: String): Any? { require(containsKey(k)) { "RUN_JSON_KEY_MISSING:$k" }; return get(k) }
    private fun Map<String, Any?>.string(k: String) = value(k) as? String ?: error("RUN_JSON_STRING_REQUIRED:$k")
    private fun Map<String, Any?>.nullString(k: String): String? = value(k)?.let { it as? String ?: error("RUN_JSON_STRING_REQUIRED:$k") }
    private fun Map<String, Any?>.long(k: String) = value(k) as? Long ?: error("RUN_JSON_INTEGER_REQUIRED:$k")
    private fun Map<String, Any?>.nullLong(k: String): Long? = value(k)?.let { it as? Long ?: error("RUN_JSON_INTEGER_REQUIRED:$k") }
    private fun Map<String, Any?>.int(k: String): Int = long(k).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "RUN_JSON_INTEGER_RANGE:$k" } }.toInt()
    private fun Map<String, Any?>.list(k: String) = value(k) as? List<*> ?: error("RUN_JSON_ARRAY_REQUIRED:$k")

    private object Json {
        fun write(v: Any?): String = when (v) {
            null -> "null"
            is Boolean, is Int, is Long -> v.toString()
            is String -> buildString {
                append('"')
                for (c in v) when (c) {
                    '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                    else -> if (c.code < 32 || c.isSurrogate()) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
                }
                append('"')
            }
            is Map<*, *> -> v.entries.joinToString(",", "{", "}") { write(it.key as String) + ":" + write(it.value) }
            is List<*> -> v.joinToString(",", "[", "]") { write(it) }
            else -> error("RUN_JSON_UNSUPPORTED_VALUE")
        }
        fun parse(text: String): Any? = Parser(text).parse()
        private class Parser(private val text: String) {
            private var pos = 0
            private fun fail(): Nothing = error("RUN_JSON_INVALID_AT:$pos")
            private fun ws() { while (pos < text.length && text[pos] in " \r\n\t") pos++ }
            private fun take(c: Char): Boolean { ws(); return if (pos < text.length && text[pos] == c) { pos++; true } else false }
            fun parse(): Any? { val v = value(0); ws(); if (pos != text.length) fail(); return v }
            private fun value(depth: Int): Any? {
                if (depth > 64) fail()
                ws(); if (pos >= text.length) fail()
                return when (text[pos]) {
                    '{' -> {
                        pos++; val map = linkedMapOf<String, Any?>()
                        if (!take('}')) do {
                            ws(); if (pos >= text.length || text[pos] != '"') fail()
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
            private fun literal(s: String, v: Any?): Any? {
                if (!text.startsWith(s, pos)) fail(); pos += s.length; return v
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
                        val c = text[pos++]
                        if (c == '"') return@buildString
                        if (c.code < 32) fail()
                        if (c != '\\') append(c) else {
                            if (pos >= text.length) fail()
                            when (val e = text[pos++]) {
                                '"', '\\', '/' -> append(e)
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
