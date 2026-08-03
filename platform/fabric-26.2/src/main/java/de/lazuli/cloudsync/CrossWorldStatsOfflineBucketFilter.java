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
 * <p>Extracted out of {@code CrossWorldStatsCloudSyncAdapter} (a private
 * nested class of {@code SteamCloudSyncClientInitializer}, which is awkward
 * to unit-test directly since it requires a full Minecraft/Fabric
 * composition-root context) so this one narrow, high-risk rule (accidentally
 * syncing or clobbering the offline bucket would silently lose local-only
 * player data) has direct test coverage on a plain JVM.
 */
public final class CrossWorldStatsOfflineBucketFilter {

    public static final String OFFLINE_KEY = "offline";

    private CrossWorldStatsOfflineBucketFilter() {
    }

    /**
     * @param localAccounts the full on-disk accounts map
     * @return a copy of {@code localAccounts} with the {@code "offline"} entry
     *         (if present) removed -- safe to serialize and upload to Cloud
     */
    public static Map<String, AccountStats> filterForExport(Map<String, AccountStats> localAccounts) {
        Map<String, AccountStats> filtered = new LinkedHashMap<>(localAccounts);
        filtered.remove(OFFLINE_KEY);
        return Map.copyOf(filtered);
    }

    /**
     * Merges an incoming Cloud payload back onto the local accounts map,
     * always preserving whatever local {@code "offline"} entry currently
     * exists (if any), regardless of what the incoming payload contains --
     * defends against a malformed/legacy payload that did include one.
     *
     * @param localAccounts    the full on-disk accounts map, before import
     * @param incomingAccounts the just-downloaded Cloud payload's accounts map
     * @return the merged map to write back to disk: every non-{@code
     *         "offline"} entry from {@code incomingAccounts}, plus the local
     *         {@code "offline"} entry unconditionally preserved
     */
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
