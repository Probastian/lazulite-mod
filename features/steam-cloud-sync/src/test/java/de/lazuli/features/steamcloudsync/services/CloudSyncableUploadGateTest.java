package de.lazuli.features.steamcloudsync.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CloudSyncableUploadGateTest {

    @Test
    void noPriorRecordedStateTreatsAsChanged(@TempDir Path tempDir) {
        CloudSyncableUploadGate gate = new CloudSyncableUploadGate(tempDir.resolve("state.json"), w -> { });

        assertThat(gate.hasChangedSinceLastUpload("options", 1_000L)).isTrue();
    }

    @Test
    void unchangedTimestampIsNotConsideredChanged(@TempDir Path tempDir) {
        CloudSyncableUploadGate gate = new CloudSyncableUploadGate(tempDir.resolve("state.json"), w -> { });

        gate.recordUploadedState("options", 1_000L);

        assertThat(gate.hasChangedSinceLastUpload("options", 1_000L)).isFalse();
    }

    @Test
    void newerTimestampIsConsideredChanged(@TempDir Path tempDir) {
        CloudSyncableUploadGate gate = new CloudSyncableUploadGate(tempDir.resolve("state.json"), w -> { });

        gate.recordUploadedState("options", 1_000L);

        assertThat(gate.hasChangedSinceLastUpload("options", 2_000L)).isTrue();
    }

    @Test
    void recordedStatePersistsAcrossANewInstanceAgainstTheSameFile(@TempDir Path tempDir) {
        Path statePath = tempDir.resolve("state.json");
        CloudSyncableUploadGate first = new CloudSyncableUploadGate(statePath, w -> { });
        first.recordUploadedState("servers-dat", 4_000L);

        CloudSyncableUploadGate second = new CloudSyncableUploadGate(statePath, w -> { });

        assertThat(second.hasChangedSinceLastUpload("servers-dat", 4_000L)).isFalse();
        assertThat(second.hasChangedSinceLastUpload("servers-dat", 4_001L)).isTrue();
    }

    @Test
    void differentCloudSyncIdsAreTrackedIndependently(@TempDir Path tempDir) {
        CloudSyncableUploadGate gate = new CloudSyncableUploadGate(tempDir.resolve("state.json"), w -> { });

        gate.recordUploadedState("options", 1_000L);

        assertThat(gate.hasChangedSinceLastUpload("options", 1_000L)).isFalse();
        assertThat(gate.hasChangedSinceLastUpload("servers-dat", 1_000L)).isTrue();
    }
}
