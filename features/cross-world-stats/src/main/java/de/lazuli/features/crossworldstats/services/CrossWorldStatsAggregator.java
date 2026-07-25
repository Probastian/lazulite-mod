package de.lazuli.features.crossworldstats.services;

import de.lazuli.api.crossworldstats.TrackedStat;
import de.lazuli.features.crossworldstats.config.AccountStats;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain-JVM-testable core logic (zero Minecraft/Steamworks imports): given
 * one account's currently-persisted {@link AccountStats} plus one world's
 * *current* tracked-stat values, computes the newly-earned delta for each
 * stat and returns the updated {@link AccountStats} (FR2.2-FR2.4,
 * FR3.1-FR3.4).
 *
 * <p>Mirrors {@code WorldSyncPreferenceService}'s own plain-JVM-testable
 * shape in {@code features/steam-cloud-sync} -- no Minecraft/Steamworks
 * import here at all; {@code currentWorldValues} is handed in by the
 * platform Version Adapter as an already-resolved {@code Map<TrackedStat,
 * Long>}.
 */
public final class CrossWorldStatsAggregator {

    /**
     * Computes one merge step for a single (account, world) pair.
     *
     * @param existing           the account's currently-persisted stats
     *                            (totals + per-world baselines)
     * @param worldId             a stable identifier for the world just read
     *                            (FR3.1) -- opaque to this class, never
     *                            resolved here
     * @param currentWorldValues  the world's *current* tracked-stat values,
     *                            as read from the live in-memory
     *                            {@code StatisticsManager} (FR2.2)
     * @return the updated {@link AccountStats}: cumulative totals plus this
     * world's re-baselined "last known value" record
     */
    public AccountStats merge(AccountStats existing, String worldId, Map<TrackedStat, Long> currentWorldValues) {
        Map<TrackedStat, Long> newTotals = new EnumMap<>(TrackedStat.class);
        newTotals.putAll(existing.totals());

        Map<TrackedStat, Long> existingBaselineForWorld = existing.worldBaselines().getOrDefault(worldId, Map.of());
        Map<TrackedStat, Long> newBaselineForWorld = new EnumMap<>(TrackedStat.class);
        newBaselineForWorld.putAll(existingBaselineForWorld);

        for (Map.Entry<TrackedStat, Long> entry : currentWorldValues.entrySet()) {
            TrackedStat stat = entry.getKey();
            long current = entry.getValue() == null ? 0L : entry.getValue();
            Long baseline = existingBaselineForWorld.get(stat);

            long delta;
            if (baseline == null) {
                // FR3.3: first-read baseline -- never observed this
                // (world, stat) pair before, so no retroactive lump sum.
                delta = 0L;
            } else {
                // FR2.4: never subtract a negative delta (e.g. a restored
                // older backup) -- clamp to 0 and re-baseline downward too.
                delta = Math.max(0L, current - baseline);
            }

            newTotals.merge(stat, delta, Long::sum);
            newBaselineForWorld.put(stat, current);
        }

        Map<String, Map<TrackedStat, Long>> newBaselines = new LinkedHashMap<>(existing.worldBaselines());
        newBaselines.put(worldId, newBaselineForWorld);

        return new AccountStats(newTotals, newBaselines);
    }
}
