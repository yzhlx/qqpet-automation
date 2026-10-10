package com.ynk.virtualdisplay.ui.log

import androidx.compose.ui.graphics.Color
import com.ynk.virtualdisplay.ui.data.LogEntry
import com.ynk.virtualdisplay.ui.data.PetEvidenceRepository
import com.ynk.virtualdisplay.ui.theme.PetTokens
import org.json.JSONObject

/**
 * Turns raw `files/pet_mvp.log` lines into a readable timeline.
 *
 * The vocabulary below is the REAL one written by the business layer, verified against a completed
 * 3-round session on the calibrated device: INITIAL_NAVIGATION, CHECK_STATE, SELECT_COURSE,
 * VERIFY_READY, RECOVER_IF_NEEDED, START_COURSE, WAIT_HIGH_FIVE_WINDOW, HIGH_FIVE, WAIT_RESULT,
 * CLEAR_PREVIOUS_RESULT, READ_POST_STATE, NEXT_ROUND, DONE, STOPPED, SESSION.
 *
 * Two honesty rules:
 *  1. Frame-chatter is dropped. A real session emits thousands of `round-N:FRAME` capture
 *     telemetry lines (~4281 in the sample run); showing them would bury every meaningful event.
 *     Dropping noise is not the same as dropping failures — errors and stop reasons are always kept.
 *  2. Text states what was observed, never more. "发出鼓励" is labelled 计入未知 when the layer
 *     itself recorded SENT_UNCONFIRMED, because sending input is not proof the game registered it.
 */
data class PetLogEntry(
    val time: String,
    val text: String,
    val dot: Color,
    val chip: String? = null,
    val chipBg: Color = PetTokens.GraySoft,
    val chipFg: Color = PetTokens.Ink2,
    /** How many consecutive identical events this row stands for; 1 for a single occurrence. */
    val occurrences: Int = 1,
) {
    val key: String get() = "$time|$text|$chip|$occurrences"
}

/** True for a capture-telemetry line that carries no user-meaningful outcome. */
private fun isFrameChatter(entry: LogEntry): Boolean {
    val action = entry.action
    if (action.endsWith(":FRAME")) return true
    // Template-matching probes are per-step page confirmations; the round summary already covers them.
    return false
}

/**
 * Collapses runs of the identical event into one row.
 *
 * A 10-minute course re-confirms "学习画面已确认" on every poll — hundreds of identical rows that bury
 * everything else and make the log unreadable exactly when it is needed. Only *consecutive* repeats
 * collapse, so a second occurrence after other events still shows up, and the count is displayed so
 * nothing is hidden.
 *
 * `internal` so the JVM unit tests can exercise it directly: the caller normally feeds it rows built
 * by [PetLogPresentation.describe], which parses JSON and therefore cannot run off-device.
 */
internal fun collapseRepeats(rows: List<PetLogEntry>): List<PetLogEntry> {
    if (rows.isEmpty()) return rows
    val out = ArrayList<PetLogEntry>(rows.size)
    for (row in rows) {
        val prev = out.lastOrNull()
        if (prev != null && prev.text == row.text && prev.chip == row.chip) {
            out[out.size - 1] = row.copy(occurrences = prev.occurrences + 1)
        } else {
            out += row
        }
    }
    return out
}

object PetLogPresentation {

    /** Full timeline (meaningful events only), oldest first, capped to the newest [limit] rows. */
    fun timeline(entries: List<LogEntry>, limit: Int = 400): List<PetLogEntry> =
        collapseRepeats(
            entries.asSequence()
                .filterNot { isFrameChatter(it) }
                .mapNotNull { describe(it) }
                .toList(),
        ).takeLast(limit)

    /** Every raw line, including frame telemetry, for the full-log viewer. */
    fun rawTimeline(entries: List<LogEntry>, limit: Int = 800): List<PetLogEntry> =
        entries.asSequence().mapNotNull { describe(it, raw = true) }.toList().takeLast(limit)

    /** Null means "this line carries nothing worth showing". */
    private fun describe(entry: LogEntry, raw: Boolean = false): PetLogEntry? {
        val time = PetEvidenceRepository.timeOf(entry.timestamp)
        val result = entry.result
        val payload = runCatching { JSONObject(result) }.getOrNull()
        val roundPrefix = entry.action.substringBefore(':', "").takeIf { it.startsWith("round-") }
        val step = entry.action.substringAfter(':', "")

        // Raw viewer keeps the capture line as a technical row.
        if (raw && step == "FRAME") {
            val src = payload?.optString("captureSource").orEmpty()
            val miss = payload?.optBoolean("frameDeliveryMiss") == true
            return PetLogEntry(
                time,
                "取帧 ${if (src.isEmpty()) "—" else src}" + if (miss) "（回落）" else "",
                if (miss) PetTokens.AmberStrong else PetTokens.Ink3,
                if (miss) "回落" else null,
                if (miss) PetTokens.AmberSoft else PetTokens.GraySoft,
                if (miss) PetTokens.AmberInk else PetTokens.Ink2,
            )
        }

        return when (entry.state) {
            "SESSION" -> when {
                result.startsWith("SESSION_STOP_REQUEST") ->
                    PetLogEntry(time, "请求停止：${result.removePrefix("SESSION_STOP_REQUEST").trim()}",
                        PetTokens.AmberStrong)
                result.startsWith("SESSION_STOP") ->
                    PetLogEntry(time, "已停止：${result.removePrefix("SESSION_STOP").trim()}", PetTokens.Ink3)
                else -> PetLogEntry(time, result.take(60), PetTokens.Ink3)
            }

            "DONE" -> PetLogEntry(
                time,
                if (result.startsWith("MANAGED_BATCH_PASS")) "批次完成：$result" else "结束：${result.take(48)}",
                if (result.startsWith("MANAGED_BATCH_PASS")) PetTokens.Green else PetTokens.Ink3,
                if (result.startsWith("MANAGED_BATCH_PASS")) "已确认" else null,
                PetTokens.GreenSoft, PetTokens.GreenInk,
            )

            "STOPPED" -> {
                val completed = payload?.optInt("completed")
                val last = payload?.optString("lastKnownState").orEmpty()
                PetLogEntry(time, "会话停止（已完成 ${completed ?: 0} 节，最后状态 ${last.ifEmpty { "—" }}）",
                    PetTokens.Ink3)
            }

            "NEXT_ROUND" -> {
                val round = payload?.optInt("round") ?: roundPrefix?.removePrefix("round-")?.toIntOrNull()
                PetLogEntry(time, "第 ${round ?: "?"} 节完成", PetTokens.Green, "已确认", PetTokens.GreenSoft, PetTokens.GreenInk)
            }

            "RECOVER_IF_NEEDED" -> when {
                step == "feed" -> {
                    val before = payload?.optInt("stamina_before")
                    val after = payload?.optInt("stamina_after")
                    val inv = payload?.optString("inventory_after").orEmpty()
                    val invNote = if (inv.isEmpty() || inv == "UNKNOWN") "，库存未知" else "，库存 $inv"
                    PetLogEntry(time, "喂食：体力 ${before ?: "?"} → ${after ?: "?"}$invNote",
                        PetTokens.Green, "已确认", PetTokens.GreenSoft, PetTokens.GreenInk)
                }
                step == "clean" -> PetLogEntry(time, "清洁完成", PetTokens.Green, "已确认", PetTokens.GreenSoft, PetTokens.GreenInk)
                else -> pageConfirmed(time, step, payload)
            }

            "VERIFY_READY" ->
                PetLogEntry(time, "开课前健康校验", PetTokens.Blue, "已确认", PetTokens.BlueSoft, PetTokens.BlueInk)

            "START_COURSE" -> PetLogEntry(time, "开始课程", PetTokens.Green, "已确认", PetTokens.GreenSoft, PetTokens.GreenInk)

            "WAIT_HIGH_FIVE_WINDOW", "HIGH_FIVE" -> when (step) {
                "high-five-summary" -> {
                    val sent = payload?.optInt("high_five_sent_count")
                    val success = payload?.optInt("high_five_success_count")
                    val verdict = payload?.optString("high_five_result").orEmpty()
                    val confirmed = success != null && success > 0
                    PetLogEntry(
                        time,
                        "鼓励：发出 ${sent ?: 0} 次" + if (confirmed) "，确认生效 $success 次" else "，生效未确认",
                        if (confirmed) PetTokens.Green else PetTokens.AmberStrong,
                        if (confirmed) "已确认" else if (verdict == "MISS") "未生效" else "计入未知",
                        if (confirmed) PetTokens.GreenSoft else PetTokens.AmberSoft,
                        if (confirmed) PetTokens.GreenInk else PetTokens.AmberInk,
                    )
                }
                else -> null // per-burst probe frames add nothing beyond the summary
            }

            "WAIT_RESULT" -> when (step) {
                "result" -> PetLogEntry(time, "成绩页面已确认", PetTokens.Green, "已确认", PetTokens.GreenSoft, PetTokens.GreenInk)
                "learning-confirmed" -> PetLogEntry(time, "学习画面已确认", PetTokens.Green, "已确认", PetTokens.GreenSoft, PetTokens.GreenInk)
                else -> null
            }

            "READ_POST_STATE" -> when {
                step.contains("result-close-course-after") || step.contains("return-home-after") ->
                    pageConfirmed(time, step, payload)
                step == "after" -> {
                    val stamina = payload?.optInt("stamina")
                    val clean = payload?.optInt("cleanliness")
                    PetLogEntry(time, "课后读数：体力 $stamina / 清洁 $clean", PetTokens.Blue)
                }
                step.endsWith("-before") || step.endsWith("-after") -> pageConfirmed(time, step, payload)
                else -> null
            }

            "INITIAL_NAVIGATION" -> PetLogEntry(time, "启动导航", PetTokens.Ink3)
            "CHECK_STATE" -> when (step) {
                "FRAME" -> null
                else -> pageConfirmed(time, step, payload)
            }
            "SELECT_COURSE" -> pageConfirmed(time, step, payload)
            "CLEAR_PREVIOUS_RESULT" -> pageConfirmed(time, step, payload)
            else -> PetLogEntry(time, "${entry.state} · ${result.take(44)}", PetTokens.Ink3)
        }
    }

    /** A step whose `result` payload carries a template match outcome. */
    private fun pageConfirmed(time: String, step: String, payload: JSONObject?): PetLogEntry? {
        val template = payload?.optString("templateName").orEmpty()
        val outcome = payload?.optString("result").orEmpty()
        if (template.isEmpty() && outcome.isEmpty()) return null
        val passed = outcome == "PASS"
        val label = friendlyStep(step)
        return PetLogEntry(
            time,
            if (template.isEmpty()) label else "$label（$template）",
            if (passed) PetTokens.Green else PetTokens.AmberStrong,
            if (passed) null else "待确认",
            if (passed) PetTokens.GraySoft else PetTokens.AmberSoft,
            if (passed) PetTokens.Ink2 else PetTokens.AmberInk,
        )
    }

    /** `55-leave-food-after` → "确认喂食页"; unknown steps keep their raw form, never a guess. */
    private fun friendlyStep(step: String): String {
        val body = step.substringAfter('-', step)
        val phase = when {
            body.endsWith("-before") -> "前往"
            body.endsWith("-after") -> "确认"
            else -> ""
        }
        val name = body.removeSuffix("-before").removeSuffix("-after")
        // `result-continue` and friends name the button rather than the page; translate the action
        // word instead of leaking the raw step identifier.
        val action = when {
            name.contains("continue") -> "继续"
            name.contains("confirm") -> "确认"
            name.contains("skip") -> "跳过"
            name.contains("cancel") -> "取消"
            else -> null
        }
        if (action != null) return phase + action
        val page = when {
            name.contains("health") -> "健康页"
            name.contains("food") -> "喂食页"
            name.contains("academy") -> "学院页"
            name.contains("course") -> "课程页"
            name.contains("result") -> "成绩页"
            name.contains("home") -> "主页"
            name.contains("outdoor") -> "户外"
            name.contains("room") -> "房间"
            name.contains("learn") -> "学习页"
            else -> return step
        }
        return phase + page
    }
}
