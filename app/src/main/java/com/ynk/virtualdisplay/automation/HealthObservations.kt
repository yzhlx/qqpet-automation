package com.ynk.virtualdisplay.automation

data class HealthObservations(val energy: ObservedValue<Int>, val cleanliness: ObservedValue<Int>,
    val mood: ObservedValue<Int>, val barEnergy: ObservedValue<Int>, val barCleanliness: ObservedValue<Int>,
    val barMood: ObservedValue<Int>, val barReady: Boolean, val evidence: String) {
    /** Require exact, fresh digits. Retain the existing conservative bar boundary for authorization. */
    fun current(now: Long, maxAgeMs: Long = 60000): HealthDecision {
        val e = energy.requireCurrent(now,maxAgeMs); val c = cleanliness.requireCurrent(now,maxAgeMs)
        val m = mood.requireCurrent(now,maxAgeMs)
        return HealthDecision(minOf(e,barEnergy.requireCurrent(now,maxAgeMs)),
            minOf(c,barCleanliness.requireCurrent(now,maxAgeMs)),minOf(m,barMood.requireCurrent(now,maxAgeMs)),barReady)
    }
    fun fields(): Map<String,Any?> = mapOf("energy" to energy.fields(),"cleanliness" to cleanliness.fields(),
        "mood" to mood.fields(),"barEstimate" to mapOf("energy" to barEnergy.fields(),
            "cleanliness" to barCleanliness.fields(),"mood" to barMood.fields()),
        "strength" to ObservedValue.Unsupported("NO_READER_IN_SLICE1").fields(),
        "intelligence" to ObservedValue.Unsupported("NO_READER_IN_SLICE1").fields(),
        "charm" to ObservedValue.Unsupported("NO_READER_IN_SLICE1").fields(),
        "coins" to ObservedValue.Unsupported("NO_READER_IN_SLICE1").fields())
}
data class HealthDecision(val energy: Int,val cleanliness: Int,val mood: Int,val barReady: Boolean) {
    val ready get() = energy>=90 && cleanliness>=90 && mood>=90 && barReady
}
