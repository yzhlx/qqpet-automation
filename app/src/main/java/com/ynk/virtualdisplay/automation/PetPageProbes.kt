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
     * Room page IDENTITY: the pet bed / chair arm at the left of the scene, a stable SCENE region.
     *
     * It used to be the left icon column (back / gear / avatar / notebook / hat). That column is
     * QQ's own chrome, NOT the pet game: it carries the signed-in user's avatar and an unread
     * badge, both of which the app does not control. Measured on the real device (2026-10-25):
     * the column scored 0.9593 against the shipped template on a frame the app itself had
     * verified as the room page, and 0.8420 on the 22:57 frames - the badge repaint inside the
     * ROI alone aborted a managed session with `INITIAL_PAGE_UNKNOWN`. The same column also
     * leaks: it measures 0.9738..0.9888 on the FOOD page, above the shipping gate of 0.95, so
     * room only avoided misclassifying food because `initialise` tests foodScene first.
     *
     * THE TEMPLATE IS SKIN-BOUND, THE ROI IS NOT. The shipped room.png is an exact raw-pixel
     * crop of the CURRENT pet skin at this ROI, so the rescale branch is skipped. When the game
     * changes skin the template has to be re-cropped from a real room frame of that skin, or
     * every managed session dies with INITIAL_PAGE_UNKNOWN while the pet room is open.
     *
     * This bit the halloween skin (2026-11-06): the shipped template was still cropped from an
     * older skin (a blue bed and a cat), so it scored 0.68 against the real halloween room. The
     * recalibration that was supposed to fix it had cropped the template from a fixture that
     * was NOT the room at all but a QQ message list, which is why the template matched its own
     * source perfectly and still never matched the room.
     *
     * The template is now cropped from the live halloween room frame at this ROI
     * (app/src/test/resources/health-panel/room-live-display46.png, captured 2026-11-06 on
     * virtual display 46; crop sha256 3a55367f8cebaf29f660422ee79d2a44105c4a1ff5e5132f80dbaa201076340a).
     * Replay of the shipping matcher over the recorded frames:
     *   1.0000 on the live room and on the room with the health sheet open,
     *   0.7138 worst QQ message list, 0.8097 bath - clear of the 0.95 gate.
     * RoomPageProbeSkinTest locks all of this, including that the ROI did not move.
     */
    val room = experimental("room", 20, 720, 120, 840)
    /**
     * ROOM page IDENTITY, second skin: the cat room this account is on since 2026-11-06.
     *
     * The shipped room template is skin-bound (see [room]) and the account switched away from
     * the halloween skin, so on the cat skin the shipped template measured 0.63 against the real
     * room and every managed session died with INITIAL_PAGE_UNKNOWN with the pet room open on
     * the virtual display (measured 2026-11-06 on virtual display 47).
     *
     * This template is an exact raw-pixel crop of that live cat-skin room frame at the SAME ROI
     * (20,720,120,840), so the rescale branch is skipped exactly as it is for [room]. Replayed
     * over the recorded frames it scores 1.0000 on the cat-skin room and at most 0.6297 on the
     * halloween room, the QQ message list and the bath scene - all far below the 0.95 gate.
     *
     * Two skins ship as two templates instead of one, so a skin change no longer needs a code
     * change: the room is proven by whichever template the live skin matches, and a page that is
     * neither room still matches neither.
     */
    val roomCatSkin = experimental("room_skin_cat", 20, 720, 120, 840)

    /**
     * ROOM page identity: the shipped [room] template alone.
     *
     * roomCatSkin used to be in this list. Replaying the shipping matcher over a frame captured
     * from the live virtual display on 2026-11-06 (pet room open, 720x1600) gives room 1.000 on
     * it and also 1.000 on the recorded halloween room, while room_skin_cat gives 0.672 on the
     * live room. The frame room_skin_cat was cropped from, kept as the cat-skin room fixture,
     * is not the pet room at all: it is a QQ short-video feed page, with its own like, comment
     * and share chrome visible. A template cut from that page matches that page exactly and
     * nothing else, so leaving it in the identity set let any QQ video page read as the pet
     * room, after which the flows ran room actions on it. That is the defect the note on [room]
     * above already records once: calibrating a room template from a frame that is not a room.
     *
     * The probe and its asset stay in the tree for traceability, but they are not identity.
     */
    val roomProbes: List<PetProfile.Probe> = listOf(room)
    val healthPanel = experimental("health", 169, 228, 250, 415).copy(exactDrift = true)

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

    /**
     * Empty-cookie stock proof only: the bottom 「兑换食物」 bubble on the food scene.
     * Measured 2026-10-22 from the live zero-stock frame
     * `.build-tmp/device-acceptance/care-feed-scene.png` at [275,1330,450,1420).
     * Present only while cookie stock is empty; absent after a purchase lands on the plate.
     * Never page identity — always scored after foodScene has already matched.
     */
    val foodExchangeBubble = mvp("food_exchange_bubble", 275, 1330, 450, 1420)

    /**
     * Empty-soap stock proof: the bottom 「兑换洗澡道具」 bubble on the bath scene.
     * Measured 2026-10-22 from the live zero-stock frame care-bath-now.png at [250,1330,470,1420).
     * Present only while soap stock is empty. Never page identity.
     */
    val bathExchangeBubble = mvp("bath_exchange_bubble", 250, 1330, 470, 1420)

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

    /**
     * QQ HOST page identity: the orange 宠物 mini-program icon in the search result's 功能 row.
     *
     * The pet mini-program is hosted by QQ, so the mini-program's own back arrow can pop the
     * whole mini-program and leave the QQ search page behind (measured 2026-10-21, session
     * 68564b34: the outdoor-map back arrow at (80,160) landed on the QQ search page instead of
     * the pet room, and the session died with PAGE_MISMATCH:...:51-health-return-home-after).
     * Recovering from that needs a positive proof that the pet app is GONE, which this icon
     * gives: it exists only on the QQ host page.
     *
     * The asset is an exact raw-pixel crop of a real VD4 frame (`.build-tmp/vd4.png`, the QQ
     * search page after the mini-program closed), 104x104 at [48,451,152,555). Host-side replay
     * of the shipping matcher: 1.000000 on that QQ frame and at most 0.787466 on the pet room,
     * the outdoor map, the academy map, the course panel and the QQ search page before the
     * 结果 row is shown - a wide margin under the unchanged 0.97 threshold.
     */
    val qqHostSearch = experimental("qq_host_search", 48, 451, 152, 555)

    /**
     * QQ HOST page after a search for 宠物: the orange icon of the 宠物 - 功能 row, measured on
     * the device (2026-11-03). The row sits lower than [qqHostSearch] on this layout (the stored
     * icon scores 0.68 at its old spot here), so this is a separate template, cropped from a real
     * frame at [59,562,163,666). Replayed against the frames of the same search (results top and
     * scrolled) it scores 1.000000, and at most 0.78 on the pet room, the QQ main page, the search
     * box, the 小程序 tab and the WeChat login page - the same wide margin under 0.97.
     * Tapping the row's label at (224,617) opens the pet room (verified on the device).
     */
    val qqSearchPetRow = experimental("qq_search_pet_row", 59, 562, 163, 666)
    val academy = experimental("academy", 330, 158, 346, 177)
    val highFive = experimental("high_five", 284, 344, 355, 400)
    val gradeA = experimental("grade_a", 545, 385, 695, 525)
    val upgrade = experimental("upgrade", 245, 558, 480, 610)
    val careUpgradeHint = experimental("care_upgrade_hint", 250, 1160, 478, 1200)
    val careUpgradeReward = experimental("care_upgrade_reward", 240, 1020, 480, 1080)
    /**
     * QQ MESSAGE tab: the search box that opens the search page. Only exists on the message
     * tab (measured 2026-11-06 VD47: bounds [235,244,485,298), centre (360,271)). A tap here
     * opens the search page; it is the safe first anchor because it never overlaps a
     * conversation row. Shell text injection was rejected (the box throws NullPointerException).
     *
     * The asset is an exact raw-pixel crop of a real VD47 message-tab frame at this ROI
     * (captured 2026-11-06, 80x54). Replaying the shipping matcher over recorded frames:
     *   1.0000 on that frame and 1.0000 on the independent QQ message list (display 45),
     *   at most 0.9272 on the pet room, the room with the health sheet open, the bath scene,
     *   the QQ search page and the search results page - all under the unchanged 0.95 gate.
     * The margin is thinner than the room probe because QQ draws a similar light box on the
     * search page; the vertical drift retry reaches it. It still clears the gate.
     */
    val qqMessageSearchBox = experimental("qq_message_search_box", 320, 244, 400, 298)

    /**
     * QQ search page: the recent-searches history word. Measured 2026-11-06 VD47 at
     * bounds [28,731,88,772), centre (58,751). Tapping it fills the search box (the box
     * refuses injected text) and shows the pet function result row. Present only when the
     * word is in the recent-searches list, which is the common case after one manual search.
     */
    val qqSearchHistoryPet = experimental("qq_search_history_pet", 28, 731, 88, 772)

    /**
     * QQ search page: the empty search box shown before any history word is tapped, distinct
     * from [qqMessageSearchBox] which sits on the message tab. Measured 2026-11-06 VD47.
     */
    val qqSearchEmptyBox = experimental("qq_search_empty_box", 48, 176, 672, 244)

    /**
     * QQ search page: the submit button that runs the box, for the cold start where no
     * history word is available. Measured 2026-11-06 VD47 at bounds [648,179,708,237).
     */
    val qqSearchSubmit = experimental("qq_search_submit", 648, 179, 708, 237)


    fun byName(): Map<String, PetProfile.Probe> = listOf(
        home, room, healthPanel, cookie, foodScene, foodExchangeBubble, soap, bath, bathExchangeBubble, outdoor, academy, highFive,
        gradeA, upgrade, careUpgradeHint, careUpgradeReward, qqHostSearch, qqSearchPetRow, qqMessageSearchBox, qqSearchHistoryPet, qqSearchEmptyBox, qqSearchSubmit,
    ).associateBy { it.name }
}
