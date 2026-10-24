package com.ynk.virtualdisplay.bot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the screen shows about the bot. Process-wide, written by [BotService]. */
data class BotState(
    val running: Boolean = false,
    val status: String = "未运行",
    val lastReport: RunReport? = null,
    /** Newest last, capped. */
    val log: List<String> = emptyList(),
)

object BotRuntime {
    private const val MAX_LOG = 200
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val _state = MutableStateFlow(BotState())
    val state: StateFlow<BotState> = _state.asStateFlow()

    fun log(line: String) = _state.update {
        it.copy(log = (it.log + "${stamp.format(Date())} $line").takeLast(MAX_LOG))
    }

    fun started() = _state.update { it.copy(running = true, status = "运行中") }

    fun finished(report: RunReport?, status: String) {
        _state.update { it.copy(running = false, status = status, lastReport = report ?: it.lastReport) }
        log(status)
    }
}
