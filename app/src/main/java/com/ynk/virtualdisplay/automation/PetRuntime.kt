package com.ynk.virtualdisplay.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PetRuntime {
    data class Snapshot(
        val running: Boolean = false,
        val runState: SessionRunState = SessionRunState.STOPPED,
        val displayId: Int? = null,
        val status: String = "未运行",
        val cycles: Int = 0,
        val nextCheckAtMs: Long? = null,
    )
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()
    fun update(block: (Snapshot) -> Snapshot) { _state.value = block(_state.value) }
}
