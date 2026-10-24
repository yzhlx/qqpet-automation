package com.ynk.virtualdisplay.bot

/**
 * Measured tap points for the validated 720x1600@320dpi virtual display (FOA_AL00, QQ 9.3.70).
 * Every value here was measured on the device by the earlier implementation; the source file is
 * named next to each one so a game update can be re-measured in one place.
 */
object Layout {
    /** The back chevron in the sticky header of every pet sub-page (PetNavigation BACK_X/Y). */
    val back = Point(80f, 160f)
    /** QQ search result: the pet mini-program icon (PetNavigation.reenterPetApp). */
    val reopenPetApp = Point(209f, 506f)
    /** Closes the health panel (PetCareFlow close-health). */
    val closeHealth = Point(355f, 454f)
    /** Opens the health panel from the room (PetCareFlow open-health). */
    val openHealth = Point(516f, 160f)
    /** Story bubble that can cover the outdoor map's back arrow (PetNavigation.returnHomeFromMap). */
    val outdoorOverlay = Point(608f, 1450f)
    /** "继续学习" on the report card (PetProfile.continueButton). */
    val resultContinue = Point(530f, 1488f)
    /** Dismisses the level-up popup (PetNavigation.dismissUpgradeIfPresent). */
    val dismissUpgrade = Point(360f, 1248f)
    /** Dismisses the care level-up popup (PetNavigation.dismissCareUpgradeIfPresent). */
    val dismissCareUpgrade = Point(360f, 1180f)
    /** Room shortcuts to the food and bath scenes (PetCareFlow enter-food / enter-bath). */
    val enterFood = Point(638f, 720f)
    val enterBath = Point(638f, 868f)
    /** Outdoor map: the academy (PetNavigation.openAcademyCourseList). */
    val enterAcademy = Point(200f, 635f)
    /** Course detail: "去上课" (PetProfile.startButton). */
    val startCourse = Point(360f, 1384f)

    const val SETTLE_MS = 900L
}
