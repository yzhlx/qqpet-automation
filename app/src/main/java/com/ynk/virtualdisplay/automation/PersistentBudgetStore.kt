package com.ynk.virtualdisplay.automation

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.Collections

/** Legacy preferences are read only when the versioned authoritative file does not yet exist. */
data class LegacyBudgetUsage(val day: String?, val minutes: Int) {
    init { require(minutes >= 0) { "LEGACY_MINUTES_NEGATIVE" } }
}

/**
 * Android-free production persistence: fsync a sibling, then atomically replace the previous file.
 * A strict, versioned binary codec avoids the org.json JVM stub and is tested with real disk files.
 * Malformed/truncated records fail closed; they never fall back to an empty budget or reimport prefs.
 * All reads and read-modify-writes share [BudgetLedgerLock], including different store instances.
 * Status is stored by UTF enum name, including RECONCILED_STOPPED; an older APK which does not
 * recognize that status MUST fail closed at valueOf instead of refunding or importing old prefs.
 */
class PersistentBudgetStore(
    file: File,
    private val legacy: () -> LegacyBudgetUsage = { LegacyBudgetUsage(null, 0) },
    private val today: () -> String = { LocalDate.now().toString() },
) : BudgetLedgerStore {
    private val file = file.absoluteFile

    override fun read(): BudgetLedgerState = synchronized(BudgetLedgerLock.monitor) {
        if (!file.exists()) {
            val source = legacy()
            val state = BudgetLedgerState(source.day ?: today(), source.minutes, emptyMap())
            write(state)
            return@synchronized state
        }
        check(file.isFile) { "BUDGET_FILE_NOT_REGULAR" }
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                check(input.readUTF() == MAGIC && input.readInt() == VERSION) { "BUDGET_FORMAT_UNSUPPORTED" }
                val day = input.readUTF()
                val imported = input.readInt()
                val previous = linkedMapOf<String, Int>()
                repeat(readCount(input)) {
                    val key = input.readUTF()
                    check(previous.put(key, input.readInt()) == null) { "BUDGET_DUPLICATE_DAY" }
                }
                val attempts = linkedMapOf<String, BudgetAttempt>()
                repeat(readCount(input)) {
                    val id = input.readUTF()
                    val attempt = BudgetAttempt(id, input.readUTF(), input.readInt(),
                        ReserveStatus.valueOf(input.readUTF()),
                        if (input.readBoolean()) input.readUTF() else null, input.readInt())
                    check(attempts.put(id, attempt) == null) { "BUDGET_DUPLICATE_ATTEMPT" }
                }
                check(input.read() == -1) { "BUDGET_TRAILING_DATA" }
                BudgetLedgerState(day, imported, Collections.unmodifiableMap(attempts),
                    Collections.unmodifiableMap(previous))
            }
        } catch (e: Exception) {
            throw IllegalStateException("BUDGET_RECORD_INVALID:${file.name}", e)
        }
    }

    override fun write(state: BudgetLedgerState) {
        synchronized(BudgetLedgerLock.monitor) {
            val parent = file.parentFile ?: error("BUDGET_PARENT_MISSING")
            check(parent.isDirectory || parent.mkdirs()) { "BUDGET_DIRECTORY_UNAVAILABLE" }
            val pending = File(parent, file.name + ".pending")
            // Both paths are resolved from the explicitly supplied same directory; no external move.
            check(pending.parentFile.canonicalFile == parent.canonicalFile) { "BUDGET_PENDING_PATH_INVALID" }
            try {
                FileOutputStream(pending).use { stream ->
                    val output = DataOutputStream(stream)
                    output.writeUTF(MAGIC)
                    output.writeInt(VERSION)
                    output.writeUTF(state.day)
                    output.writeInt(state.legacyUsedMinutes)
                    output.writeInt(state.legacyByDay.size)
                    state.legacyByDay.toSortedMap().forEach { (day, minutes) ->
                        output.writeUTF(day); output.writeInt(minutes)
                    }
                    output.writeInt(state.attempts.size)
                    state.attempts.toSortedMap().forEach { (_, attempt) ->
                        output.writeUTF(attempt.attemptId)
                        output.writeUTF(attempt.day)
                        output.writeInt(attempt.minutes)
                        output.writeUTF(attempt.status.name)
                        output.writeBoolean(attempt.reason != null)
                        attempt.reason?.let(output::writeUTF)
                        output.writeInt(attempt.limitMinutes)
                    }
                    output.flush()
                    stream.fd.sync()
                }
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                // Keep the previous authoritative file; a pending sibling is never read as success.
                throw IllegalStateException("BUDGET_WRITE_FAILED:${file.name}", e)
            }
        }
    }

    private fun readCount(input: DataInputStream): Int = input.readInt().also {
        check(it in 0..MAX_RECORDS) { "BUDGET_RECORD_COUNT_INVALID" }
    }

    companion object {
        private const val MAGIC = "QQPET_BUDGET"
        private const val VERSION = 2
        private const val MAX_RECORDS = 1_000_000
        const val FILE_NAME = "pet-minute-budget-v2.bin"
    }
}
