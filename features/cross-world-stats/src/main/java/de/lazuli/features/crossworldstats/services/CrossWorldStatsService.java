package de.lazuli.features.crossworldstats.services;

import de.lazuli.api.crossworldstats.CrossWorldStatsFacade;
import de.lazuli.api.crossworldstats.CrossWorldStatsSnapshot;
import de.lazuli.api.crossworldstats.TrackedStat;
import de.lazuli.features.crossworldstats.config.AccountStats;
import de.lazuli.features.crossworldstats.config.CrossWorldStatsConfigIO;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Implements {@link CrossWorldStatsFacade}; owns the loaded/in-memory
 * per-account state and this feature's own rate-limited merge-hook gate
 * (mirrors {@code ServerPresenceScanner.tick()}'s own interval-gating shape),
 * invoked by the platform Version Adapter's tick callback (FR2.1). Keyed by
 * a single, fixed {@code accountKey} resolved once by the composition root
 * (FR1.1/FR1.2) -- switching Steam accounts requires a client restart, same
 * as every other account-scoped feature in this repo.
 *
 * <p>Plain-JVM-testable core ({@link CrossWorldStatsAggregator}) is used
 * internally; this class itself touches only {@code java.nio.file}/plain
 * collections, no Minecraft import -- the platform layer hands it an
 * already-resolved {@code Map<TrackedStat, Long>} via a {@link Supplier}.
 */
public final class CrossWorldStatsService implements CrossWorldStatsFacade {

    /** Default merge interval, in seconds (Performance: "at most once per auto-save interval"). */
    public static final int DEFAULT_MERGE_INTERVAL_SECONDS = 30;

    private final String accountKey;
    private final Path configPath;
    private final CrossWorldStatsConfigIO configIO;
    private final Consumer<String> warningSink;
    private final CrossWorldStatsAggregator aggregator = new CrossWorldStatsAggregator();
    private final long mergeIntervalMillis;

    private volatile Map<String, AccountStats> accounts;
    private long lastMergeAtMillis = -1L;

    public CrossWorldStatsService(
            String accountKey,
            Map<String, AccountStats> initialAccounts,
            Path configPath,
            CrossWorldStatsConfigIO configIO,
            Consumer<String> warningSink) {
        this(accountKey, initialAccounts, configPath, configIO, warningSink, DEFAULT_MERGE_INTERVAL_SECONDS);
    }

    public CrossWorldStatsService(
            String accountKey,
            Map<String, AccountStats> initialAccounts,
            Path configPath,
            CrossWorldStatsConfigIO configIO,
            Consumer<String> warningSink,
            int mergeIntervalSeconds) {
        this.accountKey = accountKey;
        this.accounts = Map.copyOf(initialAccounts);
        this.configPath = configPath;
        this.configIO = configIO;
        this.warningSink = warningSink;
        this.mergeIntervalMillis = Math.max(0, mergeIntervalSeconds) * 1000L;
    }

    /**
     * Called once per client tick by the platform Version Adapter; internally
     * rate-limited to this service's own merge interval (FR2.1/Performance).
     * A {@code null} {@code worldId} (no world currently loaded, e.g. a menu
     * screen) is a safe no-op.
     *
     * @param worldId              the current world's stable identifier
     *                             (FR3.1), or {@code null} if none is loaded
     * @param currentValuesSupplier supplies the world's current tracked-stat
     *                             values (FR2.2); only invoked when this
     *                             tick is actually due, so reading the live
     *                             {@code StatisticsManager} only happens at
     *                             the configured interval, not every tick
     */
    public void tick(String worldId, Supplier<Map<TrackedStat, Long>> currentValuesSupplier) {
        if (worldId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastMergeAtMillis >= 0 && now - lastMergeAtMillis < mergeIntervalMillis) {
            return;
        }
        lastMergeAtMillis = now;
        mergeNow(worldId, currentValuesSupplier.get());
    }

    /**
     * Forces an immediate merge, bypassing the interval gate -- used for a
     * final flush on client shutdown (FR2.1: "not only once at world-exit,"
     * but also not losing the last partial interval's progress on a clean
     * exit).
     *
     * @param worldId             the current world's stable identifier, or
     *                            {@code null} if none is loaded (no-op)
     * @param currentValuesSupplier supplies the world's current tracked-stat values
     */
    public void flush(String worldId, Supplier<Map<TrackedStat, Long>> currentValuesSupplier) {
        if (worldId == null) {
            return;
        }
        Map<TrackedStat, Long> values = currentValuesSupplier.get();
        mergeNow(worldId, values);
    }

    private synchronized void mergeNow(String worldId, Map<TrackedStat, Long> currentWorldValues) {
        AccountStats existing = accounts.getOrDefault(accountKey, AccountStats.EMPTY);
        AccountStats updated = aggregator.merge(existing, worldId, currentWorldValues);

        Map<String, AccountStats> newAccounts = new LinkedHashMap<>(accounts);
        newAccounts.put(accountKey, updated);
        accounts = Map.copyOf(newAccounts);

        String warning = configIO.save(configPath, accounts);
        if (warning != null && warningSink != null) {
            warningSink.accept(warning);
        }
    }

    @Override
    public synchronized CrossWorldStatsSnapshot currentTotals() {
        AccountStats stats = accounts.getOrDefault(accountKey, AccountStats.EMPTY);
        return new CrossWorldStatsSnapshot(accountKey, stats.totals());
    }

    @Override
    public synchronized java.util.Set<String> localWorldIdsForCurrentAccount() {
        AccountStats stats = accounts.getOrDefault(accountKey, AccountStats.EMPTY);
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (String worldId : stats.worldBaselines().keySet()) {
            if (worldId != null && worldId.startsWith("local:")) {
                result.add(worldId.substring("local:".length()));
            }
        }
        return java.util.Set.copyOf(result);
    }
}
