package de.lazuli.cloudsync;

import de.lazuli.features.crossworldstats.config.AccountStats;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR4 coverage for FR-D.2: the local {@code "offline"} Cross-World Stats
 * bucket must never be exported to Cloud, and must never be overwritten by an
 * incoming Cloud payload on import, even a malformed one that (against its
 * own export contract) does contain an {@code "offline"} entry.
 */
class CrossWorldStatsOfflineBucketFilterTest {

    @Test
    void filterForExport_excludesOfflineBucket() {
        Map<String, AccountStats> local = Map.of(
                "offline", AccountStats.EMPTY,
                "76561197960287930", AccountStats.EMPTY);

        Map<String, AccountStats> exported = CrossWorldStatsOfflineBucketFilter.filterForExport(local);

        assertThat(exported).containsOnlyKeys("76561197960287930");
    }

    @Test
    void mergeForImport_preservesLocalOfflineBucket_regardlessOfIncomingPayload() {
        AccountStats localOffline = AccountStats.EMPTY;
        Map<String, AccountStats> local = Map.of(
                "offline", localOffline,
                "76561197960287930", AccountStats.EMPTY);

        // A malformed/legacy incoming payload that (against its own export
        // contract) does include an "offline" entry -- must still be ignored.
        Map<String, AccountStats> incoming = Map.of(
                "offline", AccountStats.EMPTY,
                "76561197960287930", AccountStats.EMPTY,
                "76561197960287931", AccountStats.EMPTY);

        Map<String, AccountStats> merged = CrossWorldStatsOfflineBucketFilter.mergeForImport(local, incoming);

        assertThat(merged).containsKeys("76561197960287930", "76561197960287931", "offline");
        assertThat(merged.get("offline")).isSameAs(localOffline);
    }

    @Test
    void mergeForImport_withNoLocalOfflineBucket_addsNoOfflineEntry() {
        Map<String, AccountStats> local = Map.of("76561197960287930", AccountStats.EMPTY);
        Map<String, AccountStats> incoming = Map.of("76561197960287930", AccountStats.EMPTY);

        Map<String, AccountStats> merged = CrossWorldStatsOfflineBucketFilter.mergeForImport(local, incoming);

        assertThat(merged).doesNotContainKey("offline");
    }
}
