package com.ynk.virtualdisplay.automation

import android.graphics.Rect

/**
 * Single source of truth for page probes, usable without a live capture session so recorded
 * frames can be checked against the very same definitions the flows use.
 *
 * Identity vs target: a probe is safe as long-term page IDENTITY only when it is a stable SCENE
 * region. A game element that repaints with state (the soap button turns pale once cleanliness
 * is maxed) may be used as a tap TARGET but never as page identity. Getting this wrong aborted
 * a real 3-round run with `PAGE_MISMATCH:...:clean-after:0.9538353304284677`.
 */
object PetPageProbes {
    /** Probes captured from the QQ pet experimental UI, stored under pet_experimental/. */
    private fun experimental(name: String, l: Int, t: Int, r: Int, b: Int) =
        PetProfile.Probe(name, Rect(l, t, r, b), "pet_experimental/$name.png")

    /** Probes promoted to the MVP asset set, stored under pet_mvp/. */
    private fun mvp(name: String, l: Int, t: Int, r: Int, b: Int) =
        PetProfile.Probe(name, Rect(l, t, r, b), "pet_mvp/$name.png")

    val home = experimental("home", 600, 130, 670, 190)
    /**
     * Room page IDENTITY: the left icon column (back / gear / bell / notebook / hat). Chosen over
     * the 出门 button region because that button drifts vertically between sessions (measured
     * y1354 in one session, y1332 in the next), which broke full-frame identification with
     * INITIAL_PAGE_UNKNOWN. The icon column measured 0.9991 across two independent sessions and
     * at most 0.771 on the outdoor map, so the 0.97 threshold keeps a wide margin.
     */
    val room = experimental("room", 40, 120, 130, 560)
    val healthPanel = experimental("health", 169, 228, 250, 415)

    /**
     * The cookie/plate item. A legitimate page TARGET and legacy measured item, but NOT the FOOD
     * page IDENTITY: it is a game element whose plate/quantity rendering moves with the food
     * state. On a real current frame (t24 display4 capture, 720x1600) it scored 0.922082 - a FAIL
     * at the unchanged 0.97 - on a page that was visually the FOOD page, because the plate was
     * drawn 10 px lower than in the recorded fixtures. Kept unchanged so the historical
     * measurements and every existing test still stand.
     */
    val cookie = experimental("cookie", 280, 1255, 440, 1360)

    /**
     * FOOD page IDENTITY: a stable SCENE region (floor / cushion at the left of the room), never
     * the cookie plate or the quantity badge, which repaint with food state.
     *
     * Asset `pet_mvp/food_scene.png` is an EXACT raw-pixel crop of the fixed source frame
     * `.foreman/deliverables/qqpet-care-unblock/raw/t24-10-current-display4.png`
     * (sha256 00515d5d6c6d243abd92471a5f1bd030a2004100ce17e1ef97e0f44fb365a0fe) at
     * [0,740,200,980): the same 200x240 size as the ROI, so the runtime template rescale branch is
     * skipped exactly as it is for cookie and bath. Provenance is recorded in
     * `.foreman/deliverables/qqpet-food-scene-fix/evidence/food-scene-provenance.json`.
     *
     * The asset shipped in the APK is the authority; the acceptance evidence is the real Android
     * `TemplateMatcher` score, not any host-side replay.
     */
    /**
     * FOOD page IDENTITY (game update 2026-10-12): the doghouse + bowl station at the right.
     * Two prior regions failed in the field: the cushion (20,820,240,970) is crossed by the
     * cat, and the bottom 兑换食物 pill (275,1330,450,1420) exists ONLY while the cookie stock
     * is empty - after a purchase the pill is replaced by the purchased cookie on the plate
     * (measured scene score 0.711 through a whole confirm window and beyond). The doghouse
     * region is static in every food-scene state: 1.0 pre-purchase, 0.996 with the purchased
     * cookie waiting, 0.84 on the bath scene, 0.85 on the room and 0.80 under the open sheet,
     * so the 0.97 threshold keeps a wide margin and the sheet must never match.
     */
    val foodScene = mvp("food_scene", 480, 690, 690, 960)

    /** Measured soap gesture/tap target on the bath page. Target only, never page identity. */
    val soap = mvp("soap", 280, 1280, 440, 1400)

    /**
     * Bath page IDENTITY: a stable scene region (tub/shower area), not the soap button.
     * Measured on real frames from three independent sessions: 1.000000 on every bath frame
     * (including the pale button state) and at most 0.8620 on non-bath pages, so the 0.97
     * threshold keeps ~0.11 of margin without being lowered.
     */
    val bath = mvp("bath", 10, 690, 260, 990)

    val outdoor = experimental("outdoor", 126, 610, 275, 663)
    val academy = experimental("academy", 330, 158, 346, 177)
    val highFive = experimental("high_five", 284, 344, 355, 400)
    val gradeA = experimental("grade_a", 545, 385, 695, 525)
    val upgrade = experimental("upgrade", 245, 558, 480, 610)
    val careUpgradeHint = experimental("care_upgrade_hint", 250, 1160, 478, 1200)
    val careUpgradeReward = experimental("care_upgrade_reward", 240, 1020, 480, 1080)

    fun byName(): Map<String, PetProfile.Probe> = listOf(
        home, room, healthPanel, cookie, foodScene, soap, bath, outdoor, academy, highFive,
        gradeA, upgrade, careUpgradeHint, careUpgradeReward,
    ).associateBy { it.name }
}
