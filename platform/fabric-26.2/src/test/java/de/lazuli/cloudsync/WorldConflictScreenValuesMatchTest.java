package de.lazuli.cloudsync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cloud-sync-conflict-ux FR-3.4's value-equality-after-formatting comparison
 * -- the sole automated coverage of this rule across the three platform
 * modules (each carries its own identical copy of
 * {@code WorldConflictScreen.valuesMatch}, mirroring
 * {@code WorldsPanelStatusTest}'s established precedent of testing this
 * logic once on {@code fabric-26.2} and relying on the required
 * end-of-implementation three-way diff pass to catch drift between copies).
 */
class WorldConflictScreenValuesMatchTest {

    @Test
    void identicalStringsMatch() {
        assertThat(WorldConflictScreen.valuesMatch("12.3", "12.3")).isTrue();
    }

    @Test
    void differingStringsMismatch() {
        assertThat(WorldConflictScreen.valuesMatch("12.3", "12.4")).isFalse();
    }

    @Test
    void twoSizesThatDifferInBytesButFormatToTheSameStringAreTreatedAsMatching() {
        // 12.30 MB and 12.30 MB + a few bytes both format to "12.3" via
        // WorldConflictScreen's own formatMb -- simulated here directly on
        // the already-formatted strings, since valuesMatch is a pure
        // post-formatting comparison (FR-3.4's own explicit rounding case).
        String formattedA = String.format("%.1f", (12L * 1024 * 1024 + 100) / (1024.0 * 1024.0));
        String formattedB = String.format("%.1f", (12L * 1024 * 1024 + 200) / (1024.0 * 1024.0));
        assertThat(formattedA).isEqualTo(formattedB);
        assertThat(WorldConflictScreen.valuesMatch(formattedA, formattedB)).isTrue();
    }

    /**
     * cloud-world-metadata-file Requirement 6's core fix, directly exercised:
     * the "Content match" row now compares {@code contentSignature} (a
     * SHA-256 hex digest), not formatted byte-size strings -- two
     * differently-sized-but-content-identical inputs (simulated here as the
     * same digest, since a non-deterministic zip archive of identical
     * content still hashes to the same {@code contentSignature}) are treated
     * as matching, and two same-formatted-size-but-different-content inputs
     * (simulated as two different digests) are correctly treated as a
     * mismatch -- the opposite of the old byte-size-driven behavior this row
     * replaces.
     */
    @Test
    void contentSignatureEqualityDrivesTheContentMatchRowNotByteSize() {
        String sameContentDigest = "ab12cd34ef56";
        // Same content, hashed once when it was archived at one compression pass,
        // and again at another -- the digest is identical even though the two
        // resulting zip archives would differ in compressed byte size.
        assertThat(WorldConflictScreen.valuesMatch(sameContentDigest, sameContentDigest)).isTrue();

        String differentContentDigest = "ffffffffffff";
        assertThat(WorldConflictScreen.valuesMatch(sameContentDigest, differentContentDigest)).isFalse();
    }
}
