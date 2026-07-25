package de.lazuli.api.crossworldstats;

import java.util.EnumMap;
import java.util.Map;

/**
 * The read-only hand-off shape {@code features/main-menu}'s future
 * Statistics tab consumes: the local player's cumulative, cross-world total
 * per {@link TrackedStat}, plus the Steam-account key it belongs to.
 *
 * <p>Deviation from the specification's own illustrative shape: the spec's
 * Public API section describes a {@code long steamId64} field, but FR1.2's
 * offline fallback is itself a non-numeric sentinel ({@code "offline"}) --
 * the same key type already used to bucket the persisted aggregate file
 * (spec Configuration). This record instead exposes a single {@code String
 * accountKey}, holding either the decimal {@code localSteamId64()} value or
 * the literal {@code "offline"} sentinel, avoiding a numeric/sentinel type
 * mismatch while keeping exactly one account-identity representation across
 * this feature's persisted file and its public read contract.
 *
 * @param accountKey       the account this snapshot belongs to: either
 *                         {@code Long.toString(localSteamId64())} or the
 *                         literal {@code "offline"} sentinel (FR1.2)
 * @param cumulativeTotals the cumulative, all-time, cross-world total for
 *                         each {@link TrackedStat}; stats never yet observed
 *                         for this account are simply absent (treat as
 *                         {@code 0})
 */
public record CrossWorldStatsSnapshot(String accountKey, Map<TrackedStat, Long> cumulativeTotals) {

    public CrossWorldStatsSnapshot {
        cumulativeTotals = (cumulativeTotals == null || cumulativeTotals.isEmpty())
                ? Map.of()
                : Map.copyOf(new EnumMap<>(cumulativeTotals));
    }

    /**
     * @param stat the tracked stat to read
     * @return the cumulative total for {@code stat}, or {@code 0} if never observed
     */
    public long totalOf(TrackedStat stat) {
        return cumulativeTotals.getOrDefault(stat, 0L);
    }
}
