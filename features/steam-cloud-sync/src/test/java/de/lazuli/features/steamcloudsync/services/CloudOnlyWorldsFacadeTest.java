package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.CloudOnlyWorldSummary;
import de.lazuli.features.steamcloudsync.api.WorldCloudMetadata;
import de.lazuli.features.steamcloudsync.api.WorldFingerprint;
import de.lazuli.features.steamcloudsync.config.WorldCloudMetadataIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cloud-world-metadata-file spec Requirement 5: {@link CloudOnlyWorldsFacade}
 * attaches the richer {@link WorldCloudMetadata}-sourced fields to each
 * detected cloud-only world, falling back gracefully (Requirement 8) when no
 * metadata file exists yet for a world.
 */
class CloudOnlyWorldsFacadeTest {

    private static final class FakeCloudFileStore implements CloudFileStore {
        final Map<String, byte[]> files = new HashMap<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Optional<byte[]> read(String fileName) {
            return Optional.ofNullable(files.get(fileName));
        }

        @Override
        public boolean write(String fileName, byte[] data) {
            files.put(fileName, data);
            return true;
        }

        @Override
        public OptionalLong fileTimestamp(String fileName) {
            return OptionalLong.empty();
        }

        @Override
        public boolean delete(String fileName) {
            return files.remove(fileName) != null;
        }
    }

    private WorldCloudMigrationService newMigrationService(Path tempDir, FakeCloudFileStore cloudFileStore, WorldFingerprintCache fingerprintCache) {
        return new WorldCloudMigrationService(
                tempDir.resolve("world-cloud-migration.json"), tempDir.resolve("saves"), new NoopWorldArchiveCloudStore(),
                cloudFileStore, fingerprintCache,
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                w -> { }, m -> { });
    }

    private WorldSaveSyncService newService(Path tempDir, FakeCloudFileStore cloudFileStore, WorldFingerprintCache fingerprintCache,
            WorldCloudMigrationService migrationService) {
        return new WorldSaveSyncService(
                new NoopWorldArchiveCloudStore(), cloudFileStore,
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                new CloudSyncWorker(w -> { }), fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, w -> { }, m -> { }, new WorldSyncStatusTracker(), migrationService);
    }

    @Test
    void attachesMetadataFieldsWhenAMetadataFileExists(@TempDir Path tempDir) {
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        List<WorldFingerprint> fingerprints = new ArrayList<>();
        fingerprints.add(new WorldFingerprint("cloud_world", "Cloud World", "duck's PC", 123L));
        fingerprintCache.replaceAll(fingerprints);

        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldCloudMetadataIO metadataIO = new WorldCloudMetadataIO();
        WorldCloudMetadata metadata = new WorldCloudMetadata(
                WorldCloudMetadataIO.CURRENT_SCHEMA_VERSION, "cloud_world", "Cloud World", 5_000L,
                "1.21.11", 7L, "Survival", "Hard", false, "abc123", 123L, null);
        cloudFileStore.files.put(WorldSaveSyncService.metadataFileName("cloud_world"),
                metadataIO.serialize(metadata).getBytes(StandardCharsets.UTF_8));

        WorldCloudMigrationService migrationService = newMigrationService(tempDir, cloudFileStore, fingerprintCache);
        WorldSaveSyncService worldSaveSyncService = newService(tempDir, cloudFileStore, fingerprintCache, migrationService);
        CloudOnlyWorldsFacade facade = new CloudOnlyWorldsFacade(fingerprintCache, worldSaveSyncService, migrationService,
                message -> { });

        List<CloudOnlyWorldSummary> result = facade.listCloudOnlyWorlds(List.of());

        assertThat(result).hasSize(1);
        CloudOnlyWorldSummary summary = result.get(0);
        assertThat(summary.worldSlug()).isEqualTo("cloud_world");
        assertThat(summary.lastPlayedMillis()).isEqualTo(5_000L);
        assertThat(summary.minecraftVersion()).isEqualTo("1.21.11");
        assertThat(summary.seed()).isEqualTo(7L);
        assertThat(summary.gameMode()).isEqualTo("Survival");
        assertThat(summary.difficulty()).isEqualTo("Hard");
    }

    @Test
    void fallsBackToSentinelsWhenNoMetadataFileExists(@TempDir Path tempDir) {
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        List<WorldFingerprint> fingerprints = new ArrayList<>();
        fingerprints.add(new WorldFingerprint("old_world", "Old World", "duck's PC", 111L));
        fingerprintCache.replaceAll(fingerprints);

        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldCloudMigrationService migrationService = newMigrationService(tempDir, cloudFileStore, fingerprintCache);
        WorldSaveSyncService worldSaveSyncService = newService(tempDir, cloudFileStore, fingerprintCache, migrationService);
        CloudOnlyWorldsFacade facade = new CloudOnlyWorldsFacade(fingerprintCache, worldSaveSyncService, migrationService,
                message -> { });

        List<CloudOnlyWorldSummary> result = facade.listCloudOnlyWorlds(List.of());

        assertThat(result).hasSize(1);
        CloudOnlyWorldSummary summary = result.get(0);
        assertThat(summary.worldSlug()).isEqualTo("old_world");
        assertThat(summary.displayName()).isEqualTo("Old World");
        assertThat(summary.syncedAtTimestamp()).isEqualTo(111L);
        assertThat(summary.lastPlayedMillis()).isEqualTo(-1L);
        assertThat(summary.minecraftVersion()).isNull();
        assertThat(summary.iconBase64()).isNull();
    }
}
