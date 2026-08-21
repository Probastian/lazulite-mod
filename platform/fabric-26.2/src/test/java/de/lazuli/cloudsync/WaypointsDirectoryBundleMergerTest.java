package de.lazuli.cloudsync;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Waypoints implementation plan Risk #3 coverage: an import of a Cloud
 * envelope that only names a subset of the locally-present scope files must
 * never delete/clobber the scope files it doesn't mention.
 */
class WaypointsDirectoryBundleMergerTest {

    @Test
    void mergeForImport_untouchedLocalFileSurvivesByteIdentical() {
        String scopeAOriginal = "{\"scopeKey\":\"scope-a\"}";
        String scopeBOriginal = "{\"scopeKey\":\"scope-b\"}";
        Map<String, String> local = Map.of(
                "scope-a.json", scopeAOriginal,
                "scope-b.json", scopeBOriginal);

        // Simulates a Cloud pull where only scope-a was ever touched on the
        // other device -- scope-b.json is absent from the incoming envelope.
        String scopeAIncoming = "{\"scopeKey\":\"scope-a\",\"dimensions\":{}}";
        Map<String, String> incoming = Map.of("scope-a.json", scopeAIncoming);

        Map<String, String> merged = WaypointsDirectoryBundleMerger.mergeForImport(local, incoming);

        assertThat(merged.get("scope-a.json")).isEqualTo(scopeAIncoming);
        assertThat(merged.get("scope-b.json")).isEqualTo(scopeBOriginal);
    }

    @Test
    void mergeForImport_incomingOnlyAddsNewScopeFile() {
        Map<String, String> local = Map.of("scope-a.json", "{}");
        Map<String, String> incoming = Map.of("scope-c.json", "{\"scopeKey\":\"scope-c\"}");

        Map<String, String> merged = WaypointsDirectoryBundleMerger.mergeForImport(local, incoming);

        assertThat(merged).containsKeys("scope-a.json", "scope-c.json");
    }

    @Test
    void mergeForImport_withNoLocalFiles_justAppliesIncoming() {
        Map<String, String> local = Map.of();
        Map<String, String> incoming = Map.of("scope-a.json", "{}");

        Map<String, String> merged = WaypointsDirectoryBundleMerger.mergeForImport(local, incoming);

        assertThat(merged).containsOnlyKeys("scope-a.json");
    }
}
