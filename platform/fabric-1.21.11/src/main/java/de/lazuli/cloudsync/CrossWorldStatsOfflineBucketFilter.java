package de.lazuli.cloudsync;

import de.lazuli.features.crossworldstats.config.AccountStats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure, plain-JVM-testable filter/merge logic for FR-D.2 of the Cloud Sync
 * Restoration specification: the local {@code "offline"} (non-Steam-logged-in)
 * Cross-World Stats account bucket is never read from or written to Steam
 * Cloud.
 *
 * <p>See the {@code fabric-26.2} sibling of this class for the full JavaDoc
 * and this logic's direct unit test coverage (NFR4) -- identical, Minecraft-
 * free logic duplicated per platform module since there is no shared
 * non-Minecraft module between the three platform composition roots.
 */
public final class CrossWorldStatsOfflineBucketFilter {

    public static final String OFFLINE_KEY = "offline";

    private CrossWorldStatsOfflineBucketFilter() {
    }

    public static Map<String, AccountStats> filterForExport(Map<String, AccountStats> localAccounts) {
        Map<String, AccountStats> filtered = new LinkedHashMap<>(localAccounts);
        filtered.remove(OFFLINE_KEY);
        return Map.copyOf(filtered);
    }

    public static Map<String, AccountStats> mergeForImport(
            Map<String, AccountStats> localAccounts, Map<String, AccountStats> incomingAccounts) {
        Map<String, AccountStats> merged = new LinkedHashMap<>();
        incomingAccounts.forEach((key, value) -> {
            if (!OFFLINE_KEY.equals(key)) {
                merged.put(key, value);
            }
        });
        AccountStats localOffline = localAccounts.get(OFFLINE_KEY);
        if (localOffline != null) {
            merged.put(OFFLINE_KEY, localOffline);
        }
        return Map.copyOf(merged);
    }
}
