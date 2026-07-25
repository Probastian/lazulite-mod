package de.lazuli.features.crossworldstats.config;

import de.lazuli.api.crossworldstats.TrackedStat;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One Steam account's (or the {@code "offline"} sentinel bucket's, FR1.2)
 * persisted state: the cumulative, all-time {@code totals} per
 * {@link TrackedStat} (spec FR2.3), plus the per-world "last known value"
 * delta-bookkeeping table ({@code worldBaselines}, FR3.1/FR3.2) used to
 * compute future deltas correctly.
 *
 * @param totals         cumulative total per tracked stat; a stat never yet
 *                        observed for this account is simply absent
 * @param worldBaselines per-world-identifier baseline map (FR3.1); a world
 *                        never yet observed is simply absent
 */
public record AccountStats(Map<TrackedStat, Long> totals, Map<String, Map<TrackedStat, Long>> worldBaselines) {

    public static final AccountStats EMPTY = new AccountStats(Map.of(), Map.of());

    public AccountStats {
        totals = copyStatMap(totals);
        Map<String, Map<TrackedStat, Long>> copy = new LinkedHashMap<>();
        if (worldBaselines != null) {
            worldBaselines.forEach((worldId, perStat) -> copy.put(worldId, copyStatMap(perStat)));
        }
        worldBaselines = Map.copyOf(copy);
    }

    private static Map<TrackedStat, Long> copyStatMap(Map<TrackedStat, Long> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new EnumMap<>(source));
    }
}
