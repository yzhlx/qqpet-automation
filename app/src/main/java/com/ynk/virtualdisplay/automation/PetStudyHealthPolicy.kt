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
}
