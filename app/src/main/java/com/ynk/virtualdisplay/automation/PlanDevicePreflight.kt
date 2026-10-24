package com.ynk.virtualdisplay.automation

/** Without exact inventory, conditional funding is decidable only if care needs no items. */
object PlanDevicePreflight {
    fun careNeedsNoItemsAfterWork(energy:Int?, cleanliness:Int?, care:CareStep,
        willWork:Boolean, energyCost:Int, cleanlinessCost:Int):Boolean {
        if(energy==null || cleanliness==null) return false
        require(energyCost>=0 && cleanlinessCost>=0)
        return energy-(if(willWork) energyCost else 0)>=care.energyThreshold &&
            cleanliness-(if(willWork) cleanlinessCost else 0)>=care.cleanlinessThreshold
    }
}
