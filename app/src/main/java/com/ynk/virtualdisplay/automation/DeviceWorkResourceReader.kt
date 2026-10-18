package com.ynk.virtualdisplay.automation

/**
 * [WorkResourceReader] for the live session.
 *
 * Energy comes from the same health-panel reading the care flow already trusts. Coins have no
 * reader in this build: they are reported as [ObservedValue.Unsupported] rather than as 0, because
 * a zero here would look like a real balance and could authorise a lesson the pet cannot pay for.
 * The work ledger records the gap instead of inventing a delta.
 */
class DeviceWorkResourceReader(
    private val care: PetCareFlow,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WorkResourceReader {
    override fun snapshot(at: String): RewardRecord {
        val energy = readEnergy()
        val unread = ObservedValue.Unsupported("NO_COIN_READER")
        return RewardRecord(
            kind = RewardRecordKind.OBSERVED_DELTA,
            at = at,
            coins = unread,
            workScore = ObservedValue.Unsupported("NO_WORK_SCORE_READER"),
            energy = energy,
            evidence = "health-panel",
        )
    }

    override fun readEnergy(): ObservedValue<Int> {
        val reading = care.latestReading ?: return ObservedValue.Unknown("HEALTH_NOT_YET_READ", clock(), "no-reading")
        return reading.observations.energy
    }
}
