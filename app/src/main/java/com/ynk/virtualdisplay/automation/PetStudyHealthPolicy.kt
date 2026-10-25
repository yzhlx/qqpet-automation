package com.ynk.virtualdisplay.automation

/** User policy: only energy and cleanliness authorize study; mood is observation only. */
object PetStudyHealthPolicy {
    data class Reading(val energy:Int,val cleanliness:Int,val energyThreshold:Int=SessionPlan.DEFAULT_THRESHOLD,val cleanlinessThreshold:Int=SessionPlan.DEFAULT_THRESHOLD) {
        val ready get()=energy>=energyThreshold && cleanliness>=cleanlinessThreshold
    }
    fun read(health:HealthObservations,now:Long,energyThreshold:Int=SessionPlan.DEFAULT_THRESHOLD,cleanlinessThreshold:Int=SessionPlan.DEFAULT_THRESHOLD):Reading {
        val energy=minOf(health.energy.requireCurrent(now,60000),health.barEnergy.requireCurrent(now,60000))
        val cleanliness=minOf(health.cleanliness.requireCurrent(now,60000),health.barCleanliness.requireCurrent(now,60000))
        check(energy in 0..100 && cleanliness in 0..100) {"HEALTH_VALUE_ABNORMAL"}
        return Reading(energy,cleanliness,energyThreshold,cleanlinessThreshold)
    }
    fun requireReady(health:HealthObservations,now:Long,energyThreshold:Int=SessionPlan.DEFAULT_THRESHOLD,cleanlinessThreshold:Int=SessionPlan.DEFAULT_THRESHOLD) {
        check(read(health,now,energyThreshold,cleanlinessThreshold).ready) {"HEALTH_NOT_READY_OR_STALE_BEFORE_START"}
    }
}

class PetCareReading(val observations:HealthObservations) {
    private fun current()=PetStudyHealthPolicy.read(observations,System.currentTimeMillis())
    val energy get()=current().energy
    val cleanliness get()=current().cleanliness
    /**
     * The game's own number for 体力/清洁, when the digit was readable.
     *
     * [energy] and [cleanliness] are the conservative bound of digit and pixel bar, so they can
     * sit a point below the game's own number: measured 2026-10-21 (session 1792549392632) the
     * game showed 体力 100 while the bar estimate read 99. A decision about a FULL pet must use
     * the digit, because a pet the game itself calls full cannot be fed any further.
     */
    val digitEnergy get()=digit(observations.energy)
    val digitCleanliness get()=digit(observations.cleanliness)
    private fun digit(v:ObservedValue<Int>):Int?=(v.at(System.currentTimeMillis(),60000) as? ObservedValue.Known)?.value

    /**
     * The value AS MEASURED, without the age gate.
     *
     * The 60s freshness rule guards a decision about the pet's CURRENT state. A delta between two
     * readings is different: the earlier reading was true when it was taken, and a care iteration
     * can exceed 60s (measured 2026-10-22: a soap purchase plus a 48s scrub). Reading the old
     * value through the freshness gate threw HEALTH_STALE and stopped a care run that had already
     * reached 100/100, so the delta must use the recorded numbers.
     */
    val recordedEnergy:Int? get()=(observations.energy as? ObservedValue.Known)?.value
    val recordedCleanliness:Int? get()=(observations.cleanliness as? ObservedValue.Known)?.value

    /** True when this reading may no longer decide a consuming action. */
    fun isStale(maxAgeMs:Long=60000):Boolean {
        val now=System.currentTimeMillis()
        return listOf(observations.energy,observations.cleanliness,observations.mood).any { v ->
            v !is ObservedValue.Known || now-v.observedAt>maxAgeMs || now<v.observedAt
        }
    }
}
