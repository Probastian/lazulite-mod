package de.lazuli.features.steamcloudsync.services;

import de.lazuli.features.steamcloudsync.api.WorldFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cloud-sync-uuid-identity spec: Testing/Acceptance items 2-6.
 */
class WorldCloudMigrationServiceTest {

    private static final class FakeWorldArchiveCloudStore implements WorldArchiveCloudStore {
        final Map<String, byte[]> archives = new HashMap<>();
        boolean failStreamWrite;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean streamWrite(String fileName, byte[] data) {
            if (failStreamWrite) {
                return false;
            }
            archives.put(fileName, data);
            return true;
        }

        @Override
        public void beginAsyncRead(String fileName, AsyncReadListener listener) {
            byte[] data = archives.get(fileName);
            if (data == null) {
                listener.onFailed("not found");
                return;
            }
            listener.onChunk(data);
            listener.onComplete();
        }

        @Override
        public int fileSize(String fileName) {
            byte[] data = archives.get(fileName);
            return data == null ? -1 : data.length;
        }

        @Override
        public OptionalLong fileTimestamp(String fileName) {
            return OptionalLong.empty();
        }

        @Override
        public boolean getQuota(long[] totalBytes, long[] availableBytes) {
            return true;
        }

        @Override
        public boolean forget(String fileName) {
            return archives.remove(fileName) != null;
        }

        @Override
        public boolean deleteWorldArchive(String fileName) {
            return archives.remove(fileName) != null;
        }
    }

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

    private WorldCloudMigrationService newService(Path tempDir, FakeWorldArchiveCloudStore archiveStore,
            FakeCloudFileStore cloudFileStore, WorldFingerprintCache fingerprintCache, WorldSyncPreferenceService preferenceService) {
        return new WorldCloudMigrationService(
                tempDir.resolve("world-cloud-migration.json"), tempDir, archiveStore, cloudFileStore, fingerprintCache,
                preferenceService, w -> { }, m -> { });
    }

    @Test
    void folderAlreadyNamedWithUuidResolvesWithZeroIo(@TempDir Path tempDir) {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldSyncPreferenceService preferenceService = newPreferenceService(tempDir);
        WorldCloudMigrationService service = newService(tempDir, archiveStore, cloudFileStore, fingerprintCache, preferenceService);

        String uuidFolderName = "6f9619ff-8b86-4d01-942d-00c04fc964ff";
        UUID resolved = service.resolveCloudWorldId(uuidFolderName);

        assertThat(resolved.toString()).isEqualTo(uuidFolderName);
        assertThat(archiveStore.archives).isEmpty();
        assertThat(cloudFileStore.files).isEmpty();
    }

    @Test
    void phaseAMigratesOldKeyedArchiveAndFingerprintWithoutTouchingLocalFolder(@TempDir Path tempDir) throws Exception {
        Path oldFolder = Files.createDirectory(tempDir.resolve("New World"));
        Files.writeString(oldFolder.resolve("level.dat"), "data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-New World.zip", "old archive bytes".getBytes(StandardCharsets.UTF_8));
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        fingerprintCache.replaceAll(List.of(new WorldFingerprint("New World", "New World", "device-a", 100L)));
        WorldSyncPreferenceService preferenceService = newPreferenceService(tempDir);
        WorldCloudMigrationService service = newService(tempDir, archiveStore, cloudFileStore, fingerprintCache, preferenceService);

        UUID cloudWorldId = service.resolveCloudWorldId("New World");

        assertThat(archiveStore.archives).containsKey("lazuli-world-" + cloudWorldId + ".zip");
        assertThat(archiveStore.archives).doesNotContainKey("lazuli-world-New World.zip");
        assertThat(fingerprintCache.entries()).anyMatch(fp -> fp.worldSlug().equals(cloudWorldId.toString()));
        assertThat(fingerprintCache.entries()).noneMatch(fp -> fp.worldSlug().equals("New World"));
        assertThat(Files.exists(oldFolder)).isTrue();
        assertThat(Files.exists(tempDir.resolve(cloudWorldId.toString()))).isFalse();
    }

    @Test
    void phaseAFailureLeavesOldDataIntactAndRetriesWithSameUuid(@TempDir Path tempDir) {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-New World.zip", "old archive bytes".getBytes(StandardCharsets.UTF_8));
        archiveStore.failStreamWrite = true;
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        fingerprintCache.replaceAll(List.of(new WorldFingerprint("New World", "New World", "device-a", 100L)));
        WorldSyncPreferenceService preferenceService = newPreferenceService(tempDir);
        WorldCloudMigrationService service = newService(tempDir, archiveStore, cloudFileStore, fingerprintCache, preferenceService);

        UUID first = service.resolveCloudWorldId("New World");

        assertThat(archiveStore.archives).containsKey("lazuli-world-New World.zip");
        assertThat(fingerprintCache.entries()).anyMatch(fp -> fp.worldSlug().equals("New World"));

        archiveStore.failStreamWrite = false;
        UUID second = service.resolveCloudWorldId("New World");

        assertThat(second).isEqualTo(first);
        assertThat(archiveStore.archives).containsKey("lazuli-world-" + first + ".zip");
    }

    @Test
    void phaseBRenamesFolderAndRekeysPreference(@TempDir Path tempDir) throws Exception {
        Path oldFolder = Files.createDirectory(tempDir.resolve("New World"));
        Files.writeString(oldFolder.resolve("level.dat"), "data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldSyncPreferenceService preferenceService = newPreferenceService(tempDir);
        preferenceService.setSyncEnabled("New World", true);
        WorldCloudMigrationService service = newService(tempDir, archiveStore, cloudFileStore, fingerprintCache, preferenceService);

        UUID cloudWorldId = service.resolveCloudWorldId("New World");
        service.runPendingRenames();

        assertThat(Files.exists(oldFolder)).isFalse();
        assertThat(Files.exists(tempDir.resolve(cloudWorldId.toString()))).isTrue();
        assertThat(preferenceService.isSyncEnabled(cloudWorldId.toString())).isTrue();
        assertThat(preferenceService.isSyncEnabled("New World")).isFalse();

        List<WorldCloudMigrationService.RenameEvent> renames = service.drainRecentRenames();
        assertThat(renames).containsExactly(new WorldCloudMigrationService.RenameEvent("New World", cloudWorldId.toString()));
    }

    @Test
    void phaseBFailureLeavesFolderUnrenamedAndRetriesSucceed(@TempDir Path tempDir) throws Exception {
        Path oldFolder = Files.createDirectory(tempDir.resolve("New World"));
        Files.writeString(oldFolder.resolve("level.dat"), "data");
        // Simulate a locked target by pre-creating a colliding file at the
        // eventual UUID path is impractical (UUID unknown ahead of time);
        // instead simulate failure by making the saves directory temporarily
        // read-only is platform-fragile -- exercise the retry contract
        // directly: delete the source folder mid-flight so Files.move fails
        // with a NoSuchFileException, then recreate it and retry.
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldSyncPreferenceService preferenceService = newPreferenceService(tempDir);
        WorldCloudMigrationService service = newService(tempDir, archiveStore, cloudFileStore, fingerprintCache, preferenceService);

        UUID cloudWorldId = service.resolveCloudWorldId("New World");

        // Remove the folder so the first runPendingRenames() attempt fails safely.
        Files.delete(oldFolder.resolve("level.dat"));
        Files.delete(oldFolder);
        service.runPendingRenames();

        assertThat(Files.exists(tempDir.resolve(cloudWorldId.toString()))).isFalse();
        assertThat(service.drainRecentRenames()).isEmpty();

        // Folder reappears (e.g. lock released) -- retry succeeds.
        Files.createDirectory(oldFolder);
        Files.writeString(oldFolder.resolve("level.dat"), "data");
        service.runPendingRenames();

        assertThat(Files.exists(tempDir.resolve(cloudWorldId.toString()))).isTrue();
        assertThat(service.drainRecentRenames()).hasSize(1);
    }

    @Test
    void knownLocalCloudWorldIdsReflectsCloudMigratedEntries(@TempDir Path tempDir) throws Exception {
        Path oldFolder = Files.createDirectory(tempDir.resolve("New World"));
        Files.writeString(oldFolder.resolve("level.dat"), "data");
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldSyncPreferenceService preferenceService = newPreferenceService(tempDir);
        WorldCloudMigrationService service = newService(tempDir, archiveStore, cloudFileStore, fingerprintCache, preferenceService);

        UUID cloudWorldId = service.resolveCloudWorldId("New World");

        assertThat(service.knownLocalCloudWorldIds()).containsExactly(cloudWorldId);
    }

    private static WorldSyncPreferenceService newPreferenceService(Path tempDir) {
        WorldSyncPreferenceService service =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        service.load();
        return service;
    }
}
