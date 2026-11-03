package com.ynk.virtualdisplay.automation

/**
 * [WorkResourceReader] for the live session.
 *
 * A reward is only provable as a DIFFERENCE between two readings taken minutes apart, on two
 * different pages (the balance before the shift, the payroll after it). [snapshot] is therefore
 * suspend: it has to go and READ a frame, not hand back a cached number, because the cached number
 * would be the pre-shift balance and the delta would come out as 0 - which is exactly how a flow
 * ends up claiming a payout that never arrived.
 *
 * The reading itself is injected ([readObserved]) so the flow stays JVM-testable and so the reader
 * owns the "where do the numbers come from" question, not this class. Energy stays the care flow's
 * own health-panel reading and stays synchronous: no capture is needed for it, and an energy value
 * that has not been read yet is [ObservedValue.Unknown], never 0.
 */
class DeviceWorkResourceReader(
    /** Null only in JVM tests, where [PetFlowContext] cannot exist; energy is then Unknown, never 0. */
    private val care: PetCareFlow?,
    /** Reads the coin / 工分 figures off the current frame. Throws or reports Unknown when unreadable. */
    private val readObserved: suspend (String) -> RewardRecord,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Optional seam for JVM tests that cannot construct [PetCareFlow]. Production leaves this null
     * and reads [care.latestReading]; a non-null lambda wins so tests can feed a Known/Stale value
     * without Android capture.
     */
    private val energySource: (() -> ObservedValue<Int>)? = null,
    /** Same seam for cleanliness, which a shift also spends and therefore must also be readable. */
    private val cleanlinessSource: (() -> ObservedValue<Int>)? = null,
) : WorkResourceReader {

    /**
     * A coin balance read off the COURSE detail page, injected before the flow walks into town.
     *
     * Measured 2026-10-20: the work detail page's top bar shows only 力量/智力/魅力 and no coin
     * balance, so the pre-start snapshot taken on that page can never read coins. The course detail
     * page, which the controller visits immediately before running work, DOES state the balance
     * ("消耗 22（当前 1）…"), and its reading is the tuition check's own data. The controller
     * therefore passes it in here, and the pre-start snapshot keeps it instead of producing
     * COIN_BALANCE_UNREAD. A caller that has no such reading (e.g. a manual work start from the
     * career town) still gets the frame-read attempt and blocks when it fails.
     */
    private var coursePageBalance: ObservedValue<Int>? = null

    fun setCoursePageBalance(balance: ObservedValue<Int>?) {
        coursePageBalance = balance
    }

    override suspend fun snapshot(at: String): RewardRecord {
        val observed = readObserved(at)
        // "before-start" is taken on the work detail page, whose top bar shows only 力量/智力/魅力
        // and no coin balance (measured 2026-10-20), so the frame read can never resolve coins
        // there. The course-page reading the controller took seconds earlier stands in for that one
        // snapshot only; every other snapshot (most importantly "after-completion") must come from
        // its own frame.
        val coins = if (at == "before-start" && observed.coins !is ObservedValue.Known) {
            (coursePageBalance as? ObservedValue.Known) ?: observed.coins
        } else observed.coins
        return observed.copy(
            kind = RewardRecordKind.OBSERVED_DELTA,
            at = at,
            coins = coins,
            energy = readEnergy(),
            evidence = observed.evidence,
        )
    }

    /**
     * The pet's current energy, from the same panel reading the care flow already trusts.
     *
     * Unknown when nothing has been read yet: an unread budget must block a start, not pass it.
     *
     * Do NOT re-apply the 60s study-health freshness gate here. [PetSessionController] reads the
     * room panel as "work-prepared-health", then walks outdoor → career town → venue → option
     * (TOWN_SETTLE_MS / VENUE_SETTLE_MS alone are multi-second, and venue scrolling retries can
     * push the gap past 60s). Energy does not change during that UI walk, and turning the Known
     * panel value into Stale made [PetWorkFlow.start] report WORK_ENERGY_UNREADABLE after the
     * one-shot grant was already consumed — same class of bug as returning a stale [before]
     * from prework. Carry the care-panel Known as-is, matching [setCoursePageBalance].
     */
    override fun readEnergy(): ObservedValue<Int> {
        energySource?.let { return it() }
        val reading = care?.latestReading
            ?: return ObservedValue.Unknown("HEALTH_NOT_YET_READ", clock(), "no-reading")
        return reading.observations.energy
    }

    /**
     * The pet's current cleanliness, from the same panel reading [readEnergy] uses.
     *
     * A shift spends cleanliness as well as energy, so this is a required start fact, not a nice-to-
     * have. It carries the care-panel Known as-is for the same reason [readEnergy] does: the reading
     * was taken on the room page minutes ago, and re-applying the 60s study freshness gate here would
     * turn a Known panel value into a false blocker after the career-town walk.
     */
    override fun readCleanliness(): ObservedValue<Int> {
        cleanlinessSource?.let { return it() }
        val reading = care?.latestReading
            ?: return ObservedValue.Unknown("HEALTH_NOT_YET_READ", clock(), "no-reading")
        return reading.observations.cleanliness
    }
}
