package com.ynk.virtualdisplay.automation

enum class ObservationSource { DIGIT_RECOGNITION, CONSERVATIVE_BAR_ESTIMATE, SYSTEM }

/** Unknown is an attempted observation; Unsupported means no reader exists. No numeric defaults. */
sealed interface ObservedValue<out T> {
    data class Known<T>(val value: T, val observedAt: Long, val confidence: Double,
        val evidence: String, val source: ObservationSource) : ObservedValue<T> {
        init { require(confidence in 0.0..1.0 && evidence.isNotBlank()) }
    }
    data class Unknown(val reason: String, val attemptedAt: Long, val evidence: String) : ObservedValue<Nothing>
    data class Stale<T>(val previous: Known<T>, val checkedAt: Long, val reason: String) : ObservedValue<T>
    data class Unsupported(val reason: String) : ObservedValue<Nothing>
}

fun <T> ObservedValue<T>.at(now: Long, maxAgeMs: Long): ObservedValue<T> {
    require(maxAgeMs >= 0)
    return if (this is ObservedValue.Known && (now < observedAt || now - observedAt > maxAgeMs))
        ObservedValue.Stale(this, now, "OBSERVATION_EXPIRED_OR_CLOCK_REVERSED") else this
}

fun <T> ObservedValue<T>.requireCurrent(now: Long, maxAgeMs: Long): T = when(val current=at(now,maxAgeMs)) {
    is ObservedValue.Known -> current.value
    is ObservedValue.Unknown -> error("HEALTH_UNKNOWN:${current.reason}")
    is ObservedValue.Stale -> error("HEALTH_STALE:${current.reason}")
    is ObservedValue.Unsupported -> error("HEALTH_UNSUPPORTED:${current.reason}")
}

fun ObservedValue<*>.fields(): Map<String, Any?> = when(this) {
    is ObservedValue.Known -> mapOf("status" to "KNOWN", "value" to value, "observedAt" to observedAt,
        "confidence" to confidence,"evidence" to evidence,"source" to source.name)
    is ObservedValue.Unknown -> mapOf("status" to "UNKNOWN","reason" to reason,"attemptedAt" to attemptedAt,"evidence" to evidence)
    is ObservedValue.Stale -> mapOf("status" to "STALE","previous" to previous.fields(),"checkedAt" to checkedAt,"reason" to reason)
    is ObservedValue.Unsupported -> mapOf("status" to "UNSUPPORTED","reason" to reason)
}
