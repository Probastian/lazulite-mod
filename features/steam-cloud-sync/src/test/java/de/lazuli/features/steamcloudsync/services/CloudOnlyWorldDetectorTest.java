package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.CloudOnlyWorldSummary;
import de.lazuli.features.steamcloudsync.api.WorldFingerprint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CloudOnlyWorldDetectorTest {

    private final CloudOnlyWorldDetector detector = new CloudOnlyWorldDetector();

    @Test
    void noFoldersAndNoFingerprintsReturnsEmpty() {
        assertThat(detector.detect(List.of(), List.of())).isEmpty();
    }

    @Test
    void fingerprintWithNoMatchingFolderIsCloudOnly() {
        WorldFingerprint fingerprint = new WorldFingerprint("my_world_folder", "My World", "duck's PC", 123L);

        List<CloudOnlyWorldSummary> result = detector.detect(List.of(), List.of(fingerprint));

        assertThat(result).containsExactly(new CloudOnlyWorldSummary("my_world_folder", "My World", "duck's PC", 123L));
    }

    @Test
    void fingerprintWithMatchingLocalFolderIsExcluded() {
        WorldFingerprint fingerprint = new WorldFingerprint("my_world_folder", "My World", "duck's PC", 123L);

        List<CloudOnlyWorldSummary> result = detector.detect(List.of("my_world_folder"), List.of(fingerprint));

        assertThat(result).isEmpty();
    }

    @Test
    void mixOfMatchingAndNonMatchingFingerprints() {
        WorldFingerprint local = new WorldFingerprint("local_world", "Local World", "device", 1L);
        WorldFingerprint cloudOnly = new WorldFingerprint("cloud_world", "Cloud World", "device", 2L);

        List<CloudOnlyWorldSummary> result = detector.detect(List.of("local_world", "unrelated_world"), List.of(local, cloudOnly));

        assertThat(result).extracting(CloudOnlyWorldSummary::worldSlug).containsExactly("cloud_world");
    }
}
