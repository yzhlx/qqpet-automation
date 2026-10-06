package com.ynk.virtualdisplay.automation

/** User policy: only energy and cleanliness authorize study; mood is observation only. */
object PetStudyHealthPolicy {
    data class Reading(val energy:Int,val cleanliness:Int) {
        val ready get()=energy>=90 && cleanliness>=90
    }
    fun read(health:HealthObservations,now:Long):Reading {
        val energy=minOf(health.energy.requireCurrent(now,60000),health.barEnergy.requireCurrent(now,60000))
        val cleanliness=minOf(health.cleanliness.requireCurrent(now,60000),health.barCleanliness.requireCurrent(now,60000))
        check(energy in 0..100 && cleanliness in 0..100) {"HEALTH_VALUE_ABNORMAL"}
        return Reading(energy,cleanliness)
    }
    fun requireReady(health:HealthObservations,now:Long) {
        check(read(health,now).ready) {"HEALTH_NOT_READY_OR_STALE_BEFORE_START"}
    }
}

class PetCareReading(val observations:HealthObservations) {
    private fun current()=PetStudyHealthPolicy.read(observations,System.currentTimeMillis())
    val energy get()=current().energy
    val cleanliness get()=current().cleanliness
}
