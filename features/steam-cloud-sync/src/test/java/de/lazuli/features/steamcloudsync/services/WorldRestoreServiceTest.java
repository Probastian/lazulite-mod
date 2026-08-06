package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.RestoreHandle;
import de.lazuli.api.cloudsync.RestoreProgress;
import de.lazuli.api.cloudsync.RestoreProgressListener;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class WorldRestoreServiceTest {

    private static final class FakeWorldArchiveCloudStore implements WorldArchiveCloudStore {
        final Map<String, byte[]> archives = new HashMap<>();

        /**
         * Number of bytes delivered per {@code onChunk} call. Defaults to
         * delivering the whole file in one chunk (existing behavior); tests
         * that need to observe intermediate progress (e.g. FR6.2's milestone
         * logging) can lower this to split a file's bytes across several
         * chunks.
         */
        int chunkSize = Integer.MAX_VALUE;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean streamWrite(String fileName, byte[] data) {
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
            int offset = 0;
            while (offset < data.length) {
                int length = Math.min(chunkSize, data.length - offset);
                listener.onChunk(Arrays.copyOfRange(data, offset, offset + length));
                offset += length;
            }
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

    /** Records callbacks and counts down a latch once a terminal outcome is reached. */
    private static final class RecordingListener implements RestoreProgressListener {
        final CountDownLatch done = new CountDownLatch(1);
        final List<RestoreProgress> progressEvents = new ArrayList<>();
        volatile String completedSlug;
        volatile String failedSlug;
        volatile String failureReason;

        @Override
        public void onProgress(RestoreProgress progress) {
            progressEvents.add(progress);
        }

        @Override
        public void onComplete(String worldSlug) {
            completedSlug = worldSlug;
            done.countDown();
        }

        @Override
        public void onFailed(String worldSlug, String reason) {
            failedSlug = worldSlug;
            failureReason = reason;
            done.countDown();
        }
    }

    private static byte[] buildZipArchive(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    @Test
    void successfulRestoreExtractsFilesAndEnablesSync(@TempDir Path tempDir) throws Exception {
        Path savesDirectory = Files.createDirectory(tempDir.resolve("saves"));
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-my_world.zip",
                buildZipArchive(Map.of("level.dat", "fake level data")));

        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldRestoreService service = new WorldRestoreService(archiveStore, preferenceService, worker, savesDirectory, w -> { }, m -> { }, Mockito.mock(WorldCloudMigrationService.class));

        RecordingListener listener = new RecordingListener();
        RestoreHandle handle = service.beginRestore("my_world", "my_world", listener);

        assertThat(listener.done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(handle.worldSlug()).isEqualTo("my_world");
        assertThat(listener.completedSlug).isEqualTo("my_world");
        assertThat(listener.failedSlug).isNull();

        Path restoredFile = savesDirectory.resolve("my_world").resolve("level.dat");
        assertThat(Files.exists(restoredFile)).isTrue();
        assertThat(Files.readString(restoredFile)).isEqualTo("fake level data");
        assertThat(preferenceService.isSyncEnabled("my_world")).isTrue();
        assertThat(Files.exists(savesDirectory.resolve(".tmp-restore-my_world"))).isFalse();

        worker.shutdown();
    }

    @Test
    void collisionWithExistingLocalWorldAbortsBeforeExtraction(@TempDir Path tempDir) throws IOException {
        Path savesDirectory = Files.createDirectory(tempDir.resolve("saves"));
        // FR6.13: only a folder containing a readable level.dat counts as a
        // real, previously-created save that must never be touched -- an
        // empty directory at the same slug is a stale leftover instead (see
        // staleNonSaveLocalFolderIsAutoHealedAndRestoreProceeds below), so
        // this collision fixture must actually look like a real save.
        Path existingWorld = Files.createDirectory(savesDirectory.resolve("existing_world"));
        Files.writeString(existingWorld.resolve("level.dat"), "real save data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldRestoreService service = new WorldRestoreService(archiveStore, preferenceService, worker, savesDirectory, w -> { }, m -> { }, Mockito.mock(WorldCloudMigrationService.class));

        RecordingListener listener = new RecordingListener();
        service.beginRestore("existing_world", "existing_world", listener);

        assertThat(listener.failedSlug).isEqualTo("existing_world");
        assertThat(listener.failureReason).contains("already exists");
        assertThat(listener.completedSlug).isNull();
        assertThat(existingWorld.resolve("level.dat")).exists();

        worker.shutdown();
    }

    @Test
    void staleNonSaveLocalFolderIsAutoHealedAndRestoreProceeds(@TempDir Path tempDir) throws Exception {
        Path savesDirectory = Files.createDirectory(tempDir.resolve("saves"));
        // Mirrors the real-world stale leftover this auto-heal logic targets:
        // an empty (or session.lock-only) directory left behind by an earlier
        // aborted/cancelled restore attempt through this exact codepath, with
        // no level.dat -- must not permanently block re-downloading the world.
        Path staleFolder = Files.createDirectory(savesDirectory.resolve("stale_world"));
        Files.writeString(staleFolder.resolve("session.lock"), "0");

        byte[] archiveBytes = buildZipArchive(Map.of("level.dat", "fake level data"));
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-stale_world.zip", archiveBytes);

        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        List<String> infoLogs = new CopyOnWriteArrayList<>();
        WorldRestoreService service = new WorldRestoreService(
                archiveStore, preferenceService, worker, savesDirectory, w -> { }, infoLogs::add, Mockito.mock(WorldCloudMigrationService.class));

        RecordingListener listener = new RecordingListener();
        service.beginRestore("stale_world", "stale_world", listener);

        assertThat(listener.done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.failedSlug).isNull();
        assertThat(listener.completedSlug).isEqualTo("stale_world");
        assertThat(infoLogs).anyMatch(log -> log.contains("Clearing stale, non-save local folder"));
        assertThat(Files.readString(savesDirectory.resolve("stale_world").resolve("level.dat")))
                .isEqualTo("fake level data");

        worker.shutdown();
    }

    @Test
    void missingArchiveFailsImmediately(@TempDir Path tempDir) {
        Path savesDirectory = tempDir.resolve("saves");
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldRestoreService service = new WorldRestoreService(archiveStore, preferenceService, worker, savesDirectory, w -> { }, m -> { }, Mockito.mock(WorldCloudMigrationService.class));

        RecordingListener listener = new RecordingListener();
        service.beginRestore("never_uploaded", "never_uploaded", listener);

        assertThat(listener.failedSlug).isEqualTo("never_uploaded");
        assertThat(listener.failureReason).contains("not found");

        worker.shutdown();
    }

    @Test
    void corruptArchiveFailsAndLeavesNoStagingDirectoryOrPartialWorld(@TempDir Path tempDir) throws Exception {
        Path savesDirectory = Files.createDirectory(tempDir.resolve("saves"));
        // A valid zip's local file header parses fine, but truncating its compressed
        // data mid-stream reliably throws an IOException when the entry is actually
        // read/decompressed -- unlike plain garbage bytes, which ZipInputStream simply
        // reports as "no entries" (no exception, nothing to extract). Content must be
        // low-compressibility (not "x" repeated) so the compressed payload is large
        // enough that a substantial truncation still lands inside it, not just inside
        // the (much smaller, ZipInputStream-ignored-on-read) trailing central directory.
        StringBuilder incompressible = new StringBuilder();
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 5000; i++) {
            incompressible.append((char) (32 + random.nextInt(95)));
        }
        byte[] validArchive = buildZipArchive(Map.of("level.dat", incompressible.toString()));
        byte[] truncatedArchive = Arrays.copyOf(validArchive, validArchive.length * 3 / 5);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-corrupt_world.zip", truncatedArchive);

        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldRestoreService service = new WorldRestoreService(archiveStore, preferenceService, worker, savesDirectory, w -> { }, m -> { }, Mockito.mock(WorldCloudMigrationService.class));

        RecordingListener listener = new RecordingListener();
        service.beginRestore("corrupt_world", "corrupt_world", listener);

        assertThat(listener.done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.failedSlug).isEqualTo("corrupt_world");
        assertThat(listener.completedSlug).isNull();
        assertThat(Files.exists(savesDirectory.resolve("corrupt_world"))).isFalse();
        assertThat(Files.exists(savesDirectory.resolve(".tmp-restore-corrupt_world"))).isFalse();

        worker.shutdown();
    }

    @Test
    void milestoneLogFiresExactlyOncePerProgressBoundary(@TempDir Path tempDir) throws Exception {
        Path savesDirectory = Files.createDirectory(tempDir.resolve("saves"));

        // Pad the (otherwise arbitrarily-sized, compression-dependent) archive out to a
        // multiple of 4 bytes so splitting it into four equal chunks below lands processed
        // bytes on exactly 25/50/75/100% of the reading phase -- ZipInputStream stops
        // reading entries once it hits the central directory, so the extra trailing zero
        // bytes are never visited and do not affect extraction.
        byte[] rawArchive = buildZipArchive(Map.of("level.dat", "fake level data"));
        int paddedLength = ((rawArchive.length + 3) / 4) * 4;
        byte[] archiveBytes = Arrays.copyOf(rawArchive, paddedLength);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-milestone_world.zip", archiveBytes);
        archiveStore.chunkSize = paddedLength / 4;

        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });

        List<String> infoLogs = new CopyOnWriteArrayList<>();
        WorldRestoreService service = new WorldRestoreService(
                archiveStore, preferenceService, worker, savesDirectory, w -> { }, infoLogs::add, Mockito.mock(WorldCloudMigrationService.class));

        RecordingListener listener = new RecordingListener();
        service.beginRestore("milestone_world", "milestone_world", listener);

        assertThat(listener.done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.completedSlug).isEqualTo("milestone_world");

        List<String> milestoneLogs = infoLogs.stream()
                .filter(log -> log.contains("Cloud world download"))
                .toList();
        assertThat(milestoneLogs).hasSize(4);
        assertThat(milestoneLogs.get(0)).contains(": 25%");
        assertThat(milestoneLogs.get(1)).contains(": 50%");
        assertThat(milestoneLogs.get(2)).contains(": 75%");
        assertThat(milestoneLogs.get(3)).contains(": 100%");

        worker.shutdown();
    }

    @Test
    void droppingHandleReferenceDoesNotAbortBackgroundRestore(@TempDir Path tempDir) throws Exception {
        Path savesDirectory = Files.createDirectory(tempDir.resolve("saves"));
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-dropped_handle_world.zip",
                buildZipArchive(Map.of("level.dat", "fake level data")));

        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldRestoreService service = new WorldRestoreService(archiveStore, preferenceService, worker, savesDirectory, w -> { }, m -> { }, Mockito.mock(WorldCloudMigrationService.class));

        RecordingListener listener = new RecordingListener();
        // Intentionally discard the returned RestoreHandle -- this documents the
        // "Cancel doesn't abort" contract (FR2.2/FR2.3) at the service layer: the
        // background restore must run to completion purely off WorldRestoreService's
        // own internal state (activeRestores), never depending on the caller retaining
        // a reference to the handle it was handed back.
        service.beginRestore("dropped_handle_world", "dropped_handle_world", listener);
        System.gc();

        assertThat(listener.done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.completedSlug).isEqualTo("dropped_handle_world");
        assertThat(Files.exists(savesDirectory.resolve("dropped_handle_world").resolve("level.dat"))).isTrue();

        worker.shutdown();
    }

    @Test
    void cancelRestoreOnUnknownHandleIsHarmless(@TempDir Path tempDir) {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldRestoreService service = new WorldRestoreService(archiveStore, preferenceService, worker, tempDir, w -> { }, m -> { }, Mockito.mock(WorldCloudMigrationService.class));

        service.cancelRestore(new RestoreHandle("never_started"));

        worker.shutdown();
    }
}
