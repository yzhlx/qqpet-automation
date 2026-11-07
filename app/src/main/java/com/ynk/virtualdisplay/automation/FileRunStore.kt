package com.ynk.virtualdisplay.automation

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** One atomic document per run. No Android APIs, cached state, input callback, or restart execution.
 * Construct with File(context.filesDir, "plan-runs") when production wiring is ready.
 * Requires atomic rename support in that directory; failure is propagated, never delete-and-replace.
 * Locks cover adapters in the same process; callers must keep one owning process (not a multi-process DB).
 */
class FileRunStore private constructor(
    directory: File,
    private val commit: (File, File) -> Unit,
) : RunStore {
    constructor(directory: File) : this(directory, { temp, target ->
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    })

    /** Test seam for an I/O failure at the atomic commit boundary. */
    internal constructor(directory: File, beforeCommit: () -> Unit) : this(directory, { temp, target ->
        beforeCommit()
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    })

    private val root = directory.canonicalFile.also {
        if (!it.isDirectory && !it.mkdirs()) throw IOException("RUN_STORE_DIRECTORY_UNAVAILABLE")
    }
    private val lock = locks.computeIfAbsent(root.path) { Any() }

    private fun file(runId: String): File {
        require(ID.matches(runId)) { "RUN_STORE_ID_INVALID" }
        check(root.canonicalFile == root && root.isDirectory) { "RUN_STORE_DIRECTORY_CHANGED" }
        val result = File(root, "$runId.json")
        // Refuse a pre-existing symlink, including one pointing back inside the store.
        require(!Files.isSymbolicLink(result.toPath()) && result.canonicalFile == result.absoluteFile) {
            "RUN_STORE_PATH_INVALID"
        }
        return result
    }

    private fun read(runId: String): StoredRun? {
        val f = file(runId)
        if (!f.exists()) return null
        if (!f.isFile || f.length() > MAX_BYTES) throw IOException("RUN_STORE_INVALID_FILE")
        val bytes = f.readBytes()
        if (bytes.size > MAX_BYTES) throw IOException("RUN_STORE_TOO_LARGE")
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val record = PlanRunJsonCodec.decode(text)
        require(record.snapshot.runId == runId) { "RUN_STORE_ID_MISMATCH" }
        return record
    }

    private fun write(record: StoredRun) {
        val target = file(record.snapshot.runId)
        val bytes = PlanRunJsonCodec.encode(record).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "RUN_STORE_TOO_LARGE" }
        val temp = File.createTempFile(".run-", ".tmp", root)
        try {
            FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
            // Recheck containment after staging. Never remove the last good record on failure.
            file(record.snapshot.runId)
            commit(temp, target)
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
    }

    override fun load(runId: String): RunSnapshot? = synchronized(lock) { read(runId)?.snapshot }
    override fun save(snapshot: RunSnapshot) = synchronized(lock) {
        val old = read(snapshot.runId)
        require(old == null || old.snapshot.request == snapshot.request ||
            PlanRunJsonCodec.sameRequest(old.snapshot.request, snapshot.request)) { "RUN_STORE_REQUEST_CHANGED" }
        write(StoredRun(snapshot, old?.attempts ?: emptyList(), old?.reasons ?: emptyMap()))
    }

    override fun attempts(runId: String): List<ActivityAttempt> = synchronized(lock) {
        read(runId)?.attempts?.toList() ?: emptyList()
    }
    override fun attempt(attemptId: AttemptId): ActivityAttempt? =
        attempts(attemptId.runId).singleOrNull { it.attemptId == attemptId }

    override fun persistIntent(attempt: ActivityAttempt) = synchronized(lock) {
        require(attempt.inputPending && attempt.state in setOf(AttemptState.INPUT_PENDING, AttemptState.UNCONFIRMED)) {
            "RUN_STORE_INTENT_MUST_BE_PENDING"
        }
        val record = required(attempt.attemptId.runId)
        validateAttempt(record.snapshot, attempt)
        val prior = record.attempts.singleOrNull { it.attemptId == attempt.attemptId }
        if (prior != null) {
            // Repeated persistence is a no-op only for the identical record; never regress/re-authorize.
            require(prior == attempt) { "RUN_STORE_ATTEMPT_EXISTS_NO_REPLAY" }
        } else write(record.copy(attempts = record.attempts + attempt))
    }

    override fun persistDispatched(attemptId: AttemptId, atMs: Long) = update(attemptId) { old ->
        require(old.state in setOf(AttemptState.INPUT_PENDING, AttemptState.UNCONFIRMED)) { "RUN_STORE_DISPATCH_STATE" }
        require(old.inputDispatchedAtMs == null || old.inputDispatchedAtMs == atMs) { "RUN_STORE_DISPATCH_ALREADY_RECORDED" }
        old.copy(inputDispatchedAtMs = atMs)
    }
    override fun persistStartConfirmed(attemptId: AttemptId, atMs: Long) = update(attemptId) { old ->
        if (old.state == AttemptState.COMPLETED) old else {
            require(old.startConfirmedAtMs == null || old.startConfirmedAtMs == atMs) { "RUN_STORE_START_ALREADY_RECORDED" }
            old.copy(state = AttemptState.START_CONFIRMED, inputPending = false, startConfirmedAtMs = atMs)
        }
    }
    override fun persistCompleted(attemptId: AttemptId, atMs: Long) = update(attemptId) { old ->
        if (old.state == AttemptState.COMPLETED) old else
            old.copy(state = AttemptState.COMPLETED, inputPending = false, completionConfirmedAtMs = atMs)
    }
    override fun persistUnconfirmed(attemptId: AttemptId, reason: String) = synchronized(lock) {
        require(reason.isNotBlank()) { "RUN_STORE_REASON_BLANK" }
        val record = required(attemptId.runId)
        val old = record.attempts.singleOrNull { it.attemptId == attemptId } ?: error("RUN_STORE_ATTEMPT_MISSING")
        require(old.state != AttemptState.COMPLETED) { "RUN_STORE_COMPLETION_ALREADY_CONFIRMED" }
        write(record.copy(attempts = record.attempts.map {
            if (it.attemptId == attemptId) old.copy(state = AttemptState.UNCONFIRMED, inputPending = true) else it
        }, reasons = record.reasons + (attemptId.value to reason)))
    }

    /** The model has no reason field; preserve it in the document without changing ActivityAttempt. */
    fun unconfirmedReason(attemptId: AttemptId): String? = synchronized(lock) {
        read(attemptId.runId)?.reasons?.get(attemptId.value)
    }
    private fun required(runId: String) = read(runId) ?: error("RUN_STORE_RUN_MISSING")
    private fun update(id: AttemptId, change: (ActivityAttempt) -> ActivityAttempt) = synchronized(lock) {
        val record = required(id.runId)
        val old = record.attempts.singleOrNull { it.attemptId == id } ?: error("RUN_STORE_ATTEMPT_MISSING")
        val next = change(old)
        validateAttempt(record.snapshot, next)
        if (next != old) write(record.copy(attempts = record.attempts.map { if (it.attemptId == id) next else it }))
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private const val MAX_BYTES = 4 * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Any>()
        internal fun validateAttempt(snapshot: RunSnapshot, attempt: ActivityAttempt) {
            require(attempt.attemptId.runId == snapshot.runId) { "RUN_STORE_ATTEMPT_RUN_MISMATCH" }
            val step = snapshot.request.planSnapshot.steps.singleOrNull { it.stepId == attempt.attemptId.stepId }
                ?: error("RUN_STORE_ATTEMPT_STEP_UNKNOWN")
            require(attempt.attemptId.iteration <= if (step is StudyStep) step.rounds else 1) { "RUN_STORE_ATTEMPT_ITERATION" }
            require(if (step is CareStep) attempt.durationMinutes == 0 else attempt.durationMinutes > 0) { "RUN_STORE_ATTEMPT_DURATION" }
        }
    }
}

internal data class StoredRun(val snapshot: RunSnapshot, val attempts: List<ActivityAttempt>, val reasons: Map<String, String>)
