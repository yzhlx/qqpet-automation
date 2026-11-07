package com.ynk.virtualdisplay.ui.settings

import com.ynk.virtualdisplay.automation.PetProfile

/** Pure mapping of the settings screen's real values, read from [PetProfile] so they cannot drift. */

object SettingsFacts {

    fun displayFacts(profile: PetProfile): String =
        "${profile.displayWidth}×${profile.displayHeight} · ${profile.displayDpi}dpi"
}
