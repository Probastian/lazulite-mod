package de.lazuli.features.steamcloudsync.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorldSyncPreferenceServiceTest {

    @Test
    void unknownWorldDefaultsToDisabled(@TempDir Path tempDir) {
        WorldSyncPreferenceService service =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        service.load();

        assertThat(service.isSyncEnabled("never_seen_world")).isFalse();
    }

    @Test
    void toggleFlipsAndPersists(@TempDir Path tempDir) {
        Path path = tempDir.resolve("world-sync-preferences.json");
        WorldSyncPreferenceService service = new WorldSyncPreferenceService(path, w -> { });
        service.load();

        service.toggleSync("my_world");
        assertThat(service.isSyncEnabled("my_world")).isTrue();

        WorldSyncPreferenceService reloaded = new WorldSyncPreferenceService(path, w -> { });
        reloaded.load();
        assertThat(reloaded.isSyncEnabled("my_world")).isTrue();

        service.toggleSync("my_world");
        assertThat(service.isSyncEnabled("my_world")).isFalse();
    }

    @Test
    void markEnabledAfterRestoreSetsEnabledTrue(@TempDir Path tempDir) {
        WorldSyncPreferenceService service =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        service.load();

        service.markEnabledAfterRestore("restored_world");

        assertThat(service.isSyncEnabled("restored_world")).isTrue();
    }
}
