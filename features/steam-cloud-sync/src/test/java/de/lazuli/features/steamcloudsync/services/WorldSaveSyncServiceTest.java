package de.lazuli.features.steamcloudsync.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class WorldSaveSyncServiceTest {

    /**
     * Hand-written fake {@link WorldArchiveCloudStore} (per the
     * implementation plan's Test Strategy).
     */
    private static final class FakeWorldArchiveCloudStore implements WorldArchiveCloudStore {
        final Map<String, byte[]> archives = new HashMap<>();
        long totalQuota = 1000L;
        long availableQuota = 1000L;
        boolean failStreamWrite = false;
        boolean failGetQuota = false;

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
            if (failGetQuota) {
                return false;
            }
            totalBytes[0] = totalQuota;
            availableBytes[0] = availableQuota;
            return true;
        }

        @Override
        public boolean forget(String fileName) {
            boolean removed = archives.remove(fileName) != null;
            if (removed) {
                availableQuota += 500L;
            }
            return removed;
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
    }

    @Test
    void decideStrategyUnderThresholdUsesWholeArchive() {
        assertThat(WorldSaveSyncService.decideStrategy(10L, 50, true)).isEqualTo(WorldSaveSyncService.SyncStrategy.WHOLE_ARCHIVE);
        assertThat(WorldSaveSyncService.decideStrategy(50L * 1024 * 1024, 50, true)).isEqualTo(WorldSaveSyncService.SyncStrategy.WHOLE_ARCHIVE);
    }

    @Test
    void decideStrategyOverThresholdWithFallbackAllowedUsesSelective() {
        long overThreshold = 50L * 1024 * 1024 + 1;
        assertThat(WorldSaveSyncService.decideStrategy(overThreshold, 50, true)).isEqualTo(WorldSaveSyncService.SyncStrategy.SELECTIVE_FALLBACK);
    }

    @Test
    void decideStrategyOverThresholdWithFallbackDisallowedIsSkipped() {
        long overThreshold = 50L * 1024 * 1024 + 1;
        assertThat(WorldSaveSyncService.decideStrategy(overThreshold, 50, false)).isEqualTo(WorldSaveSyncService.SyncStrategy.SKIPPED);
    }

    @Test
    void neverChoosesSelectiveForAnUnderThresholdWorld() {
        for (long size = 0; size <= 50L * 1024 * 1024; size += 10L * 1024 * 1024) {
            assertThat(WorldSaveSyncService.decideStrategy(size, 50, true)).isNotEqualTo(WorldSaveSyncService.SyncStrategy.SELECTIVE_FALLBACK);
        }
    }

    @Test
    void computeFolderSizeBytesSumsRegularFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "12345");
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(sub.resolve("b.txt"), "1234567890");

        long size = WorldSaveSyncService.computeFolderSizeBytes(tempDir);

        assertThat(size).isEqualTo(5 + 10);
    }

    @Test
    void archiveFileNameFormat() {
        assertThat(WorldSaveSyncService.archiveFileName("my_world")).isEqualTo("lazuli-world-my_world.zip");
    }

    @Test
    void wholeArchiveUploadedAndFingerprintUpdated(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "fake level data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        List<String> notifications = new ArrayList<>();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 50, true, w -> { }, notifications::add,
                new WorldSyncStatusTracker());

        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        assertThat(archiveStore.archives).containsKey("lazuli-world-my_world.zip");
        assertThat(cloudFileStore.files).containsKey("lazuli-world-fingerprints.json");
        assertThat(Files.exists(tempDir.resolve("world-fingerprint-cache.json"))).isTrue();
    }

    @Test
    void successfulSyncMarksStatusTrackerSynced(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "fake level data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 50, true, w -> { }, m -> { },
                statusTracker);

        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        assertThat(statusTracker.statusFor("my_world")).isEqualTo(de.lazuli.api.cloudsync.WorldSyncStatusHook.SyncStatus.SYNCED);
    }

    @Test
    void writeFailureLogsByteCountAndMarksStatusTrackerError(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "fake level data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.failStreamWrite = true;
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        List<String> warnings = new ArrayList<>();
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 50, true, warnings::add, m -> { },
                statusTracker);

        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        assertThat(warnings).anyMatch(message -> message.contains("Failed to sync world \"My World\"") && message.contains("bytes"));
        assertThat(statusTracker.statusFor("my_world")).isEqualTo(de.lazuli.api.cloudsync.WorldSyncStatusHook.SyncStatus.SYNC_ERROR);
        assertThat(statusTracker.lastErrorFor("my_world")).contains("bytes");
    }

    @Test
    void skippedTooLargeMarksStatusTrackerSkipped(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("big_world"));
        Files.write(worldFolder.resolve("region.dat"), new byte[2048]);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 0, false, w -> { }, m -> { },
                statusTracker);

        service.syncWorldNow("big_world", worldFolder, "Big World");
        worker.pumpTickWork();

        assertThat(statusTracker.statusFor("big_world")).isEqualTo(de.lazuli.api.cloudsync.WorldSyncStatusHook.SyncStatus.SKIPPED_TOO_LARGE);
    }

    @Test
    void quotaCheckFailureLogsSpecificWarning(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "fake level data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.failGetQuota = true;
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        List<String> warnings = new ArrayList<>();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 50, true, warnings::add, m -> { },
                new WorldSyncStatusTracker());

        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        assertThat(warnings).anyMatch(message -> message.contains("Steam Cloud quota check failed") && message.contains("my_world"));
    }

    @Test
    void quotaStillInsufficientAfterEvictionLogsSpecificWarning(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("new_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "x".repeat(100));

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.availableQuota = 0L; // no candidates to evict -> stays insufficient

        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        List<String> warnings = new ArrayList<>();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 50, true, warnings::add, m -> { },
                new WorldSyncStatusTracker());

        service.syncWorldNow("new_world", worldFolder, "New World");
        worker.pumpTickWork();

        assertThat(warnings).anyMatch(message -> message.contains("Cloud quota still insufficient")
                && message.contains("new_world") && message.contains("evicting 0 older world(s)"));
    }

    @Test
    void overThresholdWithFallbackDisallowedNotifiesAndSkipsUpload(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("big_world"));
        byte[] bigContent = new byte[2048];
        Files.write(worldFolder.resolve("region.dat"), bigContent);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        List<String> notifications = new ArrayList<>();

        // maxWorldArchiveSizeMb=0 -> even a tiny world is "over threshold"; fallback disallowed.
        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 0, false, w -> { }, notifications::add,
                new WorldSyncStatusTracker());

        service.syncWorldNow("big_world", worldFolder, "Big World");
        worker.pumpTickWork();

        assertThat(archiveStore.archives).isEmpty();
        assertThat(notifications).anyMatch(message -> message.contains("Big World"));
    }

    @Test
    void insufficientQuotaForgetsLeastRecentlySyncedOtherArchive(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("new_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "x".repeat(100));

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-old_world.zip", new byte[10]);
        archiveStore.availableQuota = 0L; // forces forget-based freeing

        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        List<String> notifications = new ArrayList<>();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 50, true, w -> { }, notifications::add,
                new WorldSyncStatusTracker());

        // Seed the local fingerprint cache with the "old_world" entry so ensureQuota can find it.
        WorldFingerprintCacheTestHelper.seed(tempDir.resolve("world-fingerprint-cache.json"),
                "old_world", "Old World", "other-device", 1L);

        service.syncWorldNow("new_world", worldFolder, "New World");
        worker.pumpTickWork();

        assertThat(archiveStore.archives).doesNotContainKey("lazuli-world-old_world.zip");
        assertThat(archiveStore.archives).containsKey("lazuli-world-new_world.zip");
        assertThat(notifications).anyMatch(message -> message.contains("Old World"));
    }

    @Test
    void onWorldUnloadSkipsWhenPreferenceDisabled(@TempDir Path tempDir) {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 50, true, w -> { }, m -> { },
                new WorldSyncStatusTracker());

        service.onWorldUnload("disabled_world", tempDir, "Disabled World");

        Mockito.verifyNoInteractions(worker);
    }

    @Test
    void onWorldUnloadSubmitsBackgroundWorkWhenPreferenceEnabled(@TempDir Path tempDir) {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        preferenceService.setSyncEnabled("enabled_world", true);
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 50, true, w -> { }, m -> { },
                new WorldSyncStatusTracker());

        service.onWorldUnload("enabled_world", tempDir, "Enabled World");

        Mockito.verify(worker).submitBackgroundWork(Mockito.any());
    }

    /**
     * Realistic, word-salad-style compressible content (not a single repeated
     * character, which every {@code Deflater} level already compresses down
     * to the same handful of bytes) -- large enough and varied enough that
     * level 9's stronger match-finding measurably beats the previous default
     * level 6 on this exact content, unlike degenerate all-one-character input.
     */
    private static String buildCompressibleContent() {
        String[] words = {"chunk", "region", "player", "world", "block", "entity", "tile", "level", "data", "cache"};
        java.util.Random random = new java.util.Random(7);
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 200_000) {
            builder.append(words[random.nextInt(words.length)]).append('-').append(random.nextInt(50)).append(' ');
        }
        return builder.toString();
    }

    @Test
    void wholeArchiveUsesBestCompressionAndRestoresCorrectly(@TempDir Path tempDir) throws Exception {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        // Realistic, moderately (not perfectly) compressible content so the
        // level-9-vs-default-level compression difference is measurable.
        String content = buildCompressibleContent();
        Files.writeString(worldFolder.resolve("level.dat"), content);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                tempDir.resolve("world-fingerprint-cache.json"), "test-device", 50, true, w -> { }, m -> { },
                new WorldSyncStatusTracker());

        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        byte[] level9Archive = archiveStore.archives.get("lazuli-world-my_world.zip");
        assertThat(level9Archive).isNotNull();

        // Equivalent archive built at the previous default Deflater level (6), for comparison.
        java.io.ByteArrayOutputStream defaultLevelBuffer = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(defaultLevelBuffer)) {
            zip.putNextEntry(new ZipEntry("level.dat"));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] defaultLevelArchive = defaultLevelBuffer.toByteArray();

        assertThat(level9Archive.length).isLessThan(defaultLevelArchive.length);

        // Round-trip through the existing, unmodified WorldRestoreService reader.
        Path savesDirectory = Files.createDirectory(tempDir.resolve("saves"));
        WorldRestoreService restoreService =
                new WorldRestoreService(archiveStore, preferenceService, worker, savesDirectory, w -> { });
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        String[] failureReason = new String[1];
        restoreService.beginRestore("my_world", new de.lazuli.api.cloudsync.RestoreProgressListener() {
            @Override
            public void onProgress(de.lazuli.api.cloudsync.RestoreProgress progress) {
            }

            @Override
            public void onComplete(String worldSlug) {
                done.countDown();
            }

            @Override
            public void onFailed(String worldSlug, String reason) {
                failureReason[0] = reason;
                done.countDown();
            }
        });

        assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(failureReason[0]).isNull();
        assertThat(Files.readString(savesDirectory.resolve("my_world").resolve("level.dat"))).isEqualTo(content);

        worker.shutdown();
    }

    /** Small local helper writing a raw fingerprint-cache file for the quota test above. */
    private static final class WorldFingerprintCacheTestHelper {
        static void seed(Path path, String worldSlug, String displayName, String deviceLabel, long syncedAtTimestamp) throws IOException {
            String json = "{\n  \"schemaVersion\": 1,\n  \"worlds\": [\n    { \"worldSlug\": \"" + worldSlug
                    + "\", \"displayName\": \"" + displayName + "\", \"deviceLabel\": \"" + deviceLabel
                    + "\", \"syncedAtTimestamp\": " + syncedAtTimestamp + " }\n  ]\n}\n";
            Files.writeString(path, json);
        }
    }
}
