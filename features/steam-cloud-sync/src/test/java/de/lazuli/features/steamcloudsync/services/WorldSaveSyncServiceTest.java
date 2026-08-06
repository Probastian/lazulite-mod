package de.lazuli.features.steamcloudsync.services;

import de.lazuli.features.steamcloudsync.api.WorldFingerprint;
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
     * cloud-sync-uuid-identity: a deterministic (per-slug, stable within one
     * test run) stand-in for {@link WorldCloudMigrationService}, so this
     * pre-existing test suite compiles and runs against the new
     * {@code WorldSaveSyncService} constructor shape. {@code resolveCloudWorldId}
     * mints a name-derived UUID (stable for a given input slug, distinct
     * across different slugs); {@code existingCloudWorldId} mirrors the real
     * FR1.2 zero-I/O fast path (only a folder already named with a UUID
     * resolves without "migrating"). NOTE: assertions elsewhere in this file
     * that check literal old-style Cloud key strings (e.g.
     * {@code "lazuli-world-my_world.zip"}) now exercise the resolved-UUID
     * key instead, since that is this feature's actual, intended behavior
     * change -- follow-up work should update those specific assertions to
     * compute the expected key via this same {@code fakeMigrationService()}.
     */
    private static WorldCloudMigrationService fakeMigrationService() {
        // Mirrors the real service's breadcrumb bookkeeping: once
        // resolveCloudWorldId(slug) has minted a cloudWorldId for a slug,
        // existingCloudWorldId(slug) finds that same breadcrumb and returns
        // it, just as the real (persisted-to-disk) breadcrumb map would --
        // this fake keeps that same breadcrumb in memory instead.
        Map<String, java.util.UUID> resolved = new HashMap<>();
        return Mockito.mock(WorldCloudMigrationService.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            if ("resolveCloudWorldId".equals(methodName)) {
                String slug = invocation.getArgument(0);
                try {
                    return java.util.UUID.fromString(slug);
                } catch (IllegalArgumentException e) {
                    return resolved.computeIfAbsent(slug,
                            s -> java.util.UUID.nameUUIDFromBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            if ("existingCloudWorldId".equals(methodName)) {
                String slug = invocation.getArgument(0);
                try {
                    return Optional.of(java.util.UUID.fromString(slug));
                } catch (IllegalArgumentException e) {
                    return Optional.ofNullable(resolved.get(slug));
                }
            }
            if ("knownLocalCloudWorldIds".equals(methodName)) {
                return java.util.Set.of();
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    /**
     * Mirrors {@link #fakeMigrationService()}'s {@code resolveCloudWorldId}
     * stand-in (name-derived UUID from the raw slug), so assertions can
     * compute the actual Cloud key a slug resolves to under the
     * cloud-sync-uuid-identity feature instead of asserting the old,
     * pre-migration slug-keyed literal.
     */
    private static String resolvedCloudWorldId(String slug) {
        return java.util.UUID.nameUUIDFromBytes(slug.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String cloudArchiveFileName(String slug) {
        return WorldSaveSyncService.archiveFileName(resolvedCloudWorldId(slug));
    }

    private static String cloudMetadataFileName(String slug) {
        return WorldSaveSyncService.metadataFileName(resolvedCloudWorldId(slug));
    }

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

        boolean failDeleteWorldArchive = false;

        @Override
        public boolean deleteWorldArchive(String fileName) {
            if (failDeleteWorldArchive) {
                return false;
            }
            archives.remove(fileName);
            return true;
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

        final java.util.List<String> deletedFileNames = new ArrayList<>();
        boolean failDelete = false;

        @Override
        public boolean delete(String fileName) {
            if (failDelete) {
                return false;
            }
            deletedFileNames.add(fileName);
            return files.remove(fileName) != null;
        }
    }

    @Test
    void decideStrategyUnderThresholdUsesWholeArchive() {
        assertThat(WorldSaveSyncService.decideStrategy(10L, 50)).isEqualTo(WorldSaveSyncService.SyncStrategy.WHOLE_ARCHIVE);
        assertThat(WorldSaveSyncService.decideStrategy(50L * 1024 * 1024, 50)).isEqualTo(WorldSaveSyncService.SyncStrategy.WHOLE_ARCHIVE);
    }

    @Test
    void decideStrategyOverThresholdIsSkipped() {
        long overThreshold = 50L * 1024 * 1024 + 1;
        assertThat(WorldSaveSyncService.decideStrategy(overThreshold, 50)).isEqualTo(WorldSaveSyncService.SyncStrategy.SKIPPED);
    }

    @Test
    void neverChoosesAnythingOtherThanWholeArchiveOrSkippedForAnUnderThresholdWorld() {
        for (long size = 0; size <= 50L * 1024 * 1024; size += 10L * 1024 * 1024) {
            assertThat(WorldSaveSyncService.decideStrategy(size, 50)).isEqualTo(WorldSaveSyncService.SyncStrategy.WHOLE_ARCHIVE);
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
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, notifications::add,
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        assertThat(archiveStore.archives).containsKey(cloudArchiveFileName("my_world"));
        assertThat(cloudFileStore.files).containsKey("lazuli-world-fingerprints.json");
        // The fingerprint update is now RAM-only (never persisted to disk, per the
        // no-on-disk-cloud-state-cache design) -- verify via the in-memory cache
        // instead of a local file.
        assertThat(fingerprintCache.entries()).anyMatch(entry -> entry.worldSlug().equals(resolvedCloudWorldId("my_world")));
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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                statusTracker, fakeMigrationService());

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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, warnings::add, m -> { },
                statusTracker, fakeMigrationService());

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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 0, w -> { }, m -> { },
                statusTracker, fakeMigrationService());

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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, warnings::add, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        assertThat(warnings).anyMatch(message -> message.contains("Steam Cloud quota check failed") && message.contains(resolvedCloudWorldId("my_world")));
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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, warnings::add, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.syncWorldNow("new_world", worldFolder, "New World");
        worker.pumpTickWork();

        assertThat(warnings).anyMatch(message -> message.contains("Cloud quota still insufficient")
                && message.contains(resolvedCloudWorldId("new_world")) && message.contains("evicting 0 older world(s)"));
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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 0, w -> { }, notifications::add,
                new WorldSyncStatusTracker(), fakeMigrationService());

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
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        // Seed the in-memory fingerprint cache with the "old_world" entry so ensureQuota can find it.
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "old_world", "Old World", "other-device", 1L);

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, notifications::add,
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.syncWorldNow("new_world", worldFolder, "New World");
        worker.pumpTickWork();

        assertThat(archiveStore.archives).doesNotContainKey("lazuli-world-old_world.zip");
        assertThat(archiveStore.archives).containsKey(cloudArchiveFileName("new_world"));
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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.onWorldUnload("enabled_world", tempDir, "Enabled World");

        Mockito.verify(worker).submitBackgroundWork(Mockito.any());
    }

    /**
     * cloud-world-metadata-file gap fix: the platform composition roots'
     * {@code onWorldUnload} call sites now supply a real
     * {@code level.dat} NBT read via the {@code Supplier}-accepting overload
     * rather than always defaulting to {@link
     * de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch#unreadable()}
     * -- this confirms that overload both (a) invokes the supplier lazily
     * (only once the background worker actually runs, never eagerly on the
     * calling thread) and (b) flows the supplied batch's real fields all the
     * way through to the uploaded {@link
     * de.lazuli.features.steamcloudsync.api.WorldCloudMetadata}, matching
     * the equivalent {@code syncWorldNow(..., LevelDatBatch)} coverage
     * {@code syncWorldNowUploadsAMetadataFileOnASuccessfulWholeArchiveSync}
     * already has for the lower-level entry point.
     */
    @Test
    void onWorldUnloadWithSupplierLazilyReadsTheBatchAndFlowsItIntoUploadedMetadata(@TempDir Path tempDir) throws Exception {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "fake level data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        preferenceService.setSyncEnabled("my_world", true);
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch batch =
                new de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch(
                        99L, "Hard", true, 12L, "1.21.11", true, 1_700_000_000_000L, "Creative", true);
        java.util.concurrent.atomic.AtomicInteger supplierInvocationCount = new java.util.concurrent.atomic.AtomicInteger(0);

        service.onWorldUnload("my_world", worldFolder, "My World", () -> {
            supplierInvocationCount.incrementAndGet();
            return batch;
        });

        awaitBackgroundWork(worker);
        worker.pumpTickWork();

        // Invoked exactly once, and only as part of the background work this
        // checkpoint submits -- never eagerly on the calling (client tick) thread.
        assertThat(supplierInvocationCount.get()).isEqualTo(1);
        Optional<de.lazuli.features.steamcloudsync.api.WorldCloudMetadata> metadata = service.cloudMetadataFor("my_world");
        assertThat(metadata).isPresent();
        assertThat(metadata.get().minecraftVersion()).isEqualTo("1.21.11");
        assertThat(metadata.get().seed()).isEqualTo(99L);
        assertThat(metadata.get().difficulty()).isEqualTo("Hard");
        // cloud-world-entry-parity Requirement 3b: a real LevelDatBatch's
        // lastPlayedMillis/gameMode/hardcore now flow through to the
        // uploaded metadata, replacing the old "Unknown"/false/synced-at-proxy
        // sentinels.
        assertThat(metadata.get().gameMode()).isEqualTo("Creative");
        assertThat(metadata.get().hardcore()).isTrue();
        assertThat(metadata.get().lastPlayedMillis()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void onWorldUnloadFallsBackToSentinelsWhenLevelDatBatchIsUnreadable(@TempDir Path tempDir) throws Exception {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "fake level data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        preferenceService.setSyncEnabled("my_world", true);
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        long beforeSync = System.currentTimeMillis();
        service.onWorldUnload("my_world", worldFolder, "My World",
                de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch::unreadable);

        awaitBackgroundWork(worker);
        worker.pumpTickWork();

        Optional<de.lazuli.features.steamcloudsync.api.WorldCloudMetadata> metadata = service.cloudMetadataFor("my_world");
        assertThat(metadata).isPresent();
        // Regression coverage: LevelDatBatch.unreadable() still degrades to
        // the pre-existing sentinels ("Unknown"/false/this sync's own
        // timestamp as the lastPlayedMillis proxy), unchanged by
        // Requirement 3b's new real-value path.
        assertThat(metadata.get().gameMode()).isEqualTo("Unknown");
        assertThat(metadata.get().hardcore()).isFalse();
        assertThat(metadata.get().lastPlayedMillis()).isGreaterThanOrEqualTo(beforeSync);
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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        byte[] level9Archive = archiveStore.archives.get(cloudArchiveFileName("my_world"));
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
                new WorldRestoreService(archiveStore, preferenceService, worker, savesDirectory, w -> { }, m -> { }, fakeMigrationService());
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        String[] failureReason = new String[1];
        // cloud-sync-uuid-identity FR5.2/FR5.4: WorldRestoreService#beginRestore's
        // worldSlug parameter is always the already-resolved cloudWorldId (a UUID
        // string), not the raw local folder slug -- see its Javadoc.
        restoreService.beginRestore(resolvedCloudWorldId("my_world"), "My World", new de.lazuli.api.cloudsync.RestoreProgressListener() {
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
        assertThat(Files.readString(savesDirectory.resolve(resolvedCloudWorldId("my_world")).resolve("level.dat"))).isEqualTo(content);

        worker.shutdown();
    }

    // -- FR-P1: in-progress flag pairing --

    @Test
    void onWorldUnloadMarksPendingBeforeHandingOffToTheWorker(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "fake level data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        preferenceService.setSyncEnabled("my_world", true);
        // A mock worker never actually runs the submitted work, so if the flag is
        // already true immediately after onWorldUnload returns, it was set at
        // hand-off time (top of the method), not from within syncWorldNow itself.
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                statusTracker, fakeMigrationService());

        service.onWorldUnload("my_world", worldFolder, "My World");

        assertThat(statusTracker.isUploadInProgress("my_world")).isTrue();
    }

    @Test
    void successfulSyncClearsInProgressFlagAfterCompletion(@TempDir Path tempDir) throws IOException {
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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                statusTracker, fakeMigrationService());

        statusTracker.markUploadPending("my_world");
        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        assertThat(statusTracker.isUploadInProgress("my_world")).isFalse();
    }

    @Test
    void writeFailureClearsInProgressFlagAfterCompletion(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "fake level data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.failStreamWrite = true;
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                statusTracker, fakeMigrationService());

        statusTracker.markUploadPending("my_world");
        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        assertThat(statusTracker.isUploadInProgress("my_world")).isFalse();
    }

    @Test
    void skippedTooLargeClearsInProgressFlagSynchronously(@TempDir Path tempDir) throws IOException {
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
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 0, w -> { }, m -> { },
                statusTracker, fakeMigrationService());

        statusTracker.markUploadPending("big_world");
        service.syncWorldNow("big_world", worldFolder, "Big World");

        assertThat(statusTracker.isUploadInProgress("big_world")).isFalse();
    }

    @Test
    void ioExceptionDuringArchiveBuildClearsInProgressFlag(@TempDir Path tempDir) {
        Path nonExistentWorldFolder = tempDir.resolve("does_not_exist");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                statusTracker, fakeMigrationService());

        statusTracker.markUploadPending("missing_world");
        service.syncWorldNow("missing_world", nonExistentWorldFolder, "Missing World");

        assertThat(statusTracker.isUploadInProgress("missing_world")).isFalse();
    }

    // -- FR-T.1: onWorldSaved --

    @Test
    void onWorldSavedSkipsWhenPreferenceDisabled(@TempDir Path tempDir) {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.onWorldSaved("disabled_world", tempDir, "Disabled World");

        Mockito.verifyNoInteractions(worker);
    }

    @Test
    void onWorldSavedMarksPendingAndSubmitsWhenEnabledAndNotAlreadyInProgress(@TempDir Path tempDir) {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        preferenceService.setSyncEnabled("enabled_world", true);
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                statusTracker, fakeMigrationService());

        service.onWorldSaved("enabled_world", tempDir, "Enabled World");

        Mockito.verify(worker).submitBackgroundWork(Mockito.any());
        assertThat(statusTracker.isUploadInProgress("enabled_world")).isTrue();
    }

    @Test
    void onWorldSavedIsANoOpReentrancyGuardWhenAlreadyInProgress(@TempDir Path tempDir) {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        preferenceService.setSyncEnabled("enabled_world", true);
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();
        statusTracker.markUploadPending("enabled_world");

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                statusTracker, fakeMigrationService());

        service.onWorldSaved("enabled_world", tempDir, "Enabled World");

        Mockito.verifyNoInteractions(worker);
    }

    // -- FR-P3: computeFolderLastModifiedMillis / upToDateStatusFor --

    @Test
    void computeFolderLastModifiedMillisReturnsZeroForAnEmptyDir(@TempDir Path tempDir) throws IOException {
        Path empty = Files.createDirectory(tempDir.resolve("empty"));

        assertThat(WorldSaveSyncService.computeFolderLastModifiedMillis(empty)).isEqualTo(0L);
    }

    @Test
    void computeFolderLastModifiedMillisReturnsTheMaxAcrossNestedFiles(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Path older = worldFolder.resolve("older.txt");
        Files.writeString(older, "a");
        Path sub = Files.createDirectory(worldFolder.resolve("sub"));
        Path newer = sub.resolve("newer.txt");
        Files.writeString(newer, "b");

        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(5_000L));

        assertThat(WorldSaveSyncService.computeFolderLastModifiedMillis(worldFolder)).isEqualTo(5_000L);
    }

    @Test
    void computeFolderLastModifiedMillisThrowsForANonExistentPath(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does_not_exist");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WorldSaveSyncService.computeFolderLastModifiedMillis(missing))
                .isInstanceOf(IOException.class);
    }

    @Test
    void upToDateStatusForIsUnknownWithNoFingerprint(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldSaveSyncService service = newServiceForFreshnessTests(tempDir, new WorldFingerprintCache());

        assertThat(service.upToDateStatusFor("my_world", worldFolder)).isEqualTo(de.lazuli.api.cloudsync.WorldFreshnessHook.UpToDateStatus.UNKNOWN);
    }

    @Test
    void upToDateStatusForIsStaleWhenFingerprintIsFromAnotherDevice(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache,
                "my_world", "My World", "other-device", System.currentTimeMillis() + 100_000L);

        WorldSaveSyncService service = newServiceForFreshnessTests(tempDir, fingerprintCache);

        assertThat(service.upToDateStatusFor("my_world", worldFolder)).isEqualTo(de.lazuli.api.cloudsync.WorldFreshnessHook.UpToDateStatus.STALE);
    }

    @Test
    void upToDateStatusForIsStaleWhenLocalIsNewerThanThisDevicesOwnFingerprint(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Path file = worldFolder.resolve("level.dat");
        Files.writeString(file, "data");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(10_000L));
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache,
                "my_world", "My World", "test-device", 5_000L);

        WorldSaveSyncService service = newServiceForFreshnessTests(tempDir, fingerprintCache);

        assertThat(service.upToDateStatusFor("my_world", worldFolder)).isEqualTo(de.lazuli.api.cloudsync.WorldFreshnessHook.UpToDateStatus.STALE);
    }

    @Test
    void upToDateStatusForIsUpToDateWhenLocalIsNotNewerThanThisDevicesOwnFingerprint(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Path file = worldFolder.resolve("level.dat");
        Files.writeString(file, "data");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000L));
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache,
                "my_world", "My World", "test-device", 5_000L);

        WorldSaveSyncService service = newServiceForFreshnessTests(tempDir, fingerprintCache);

        assertThat(service.upToDateStatusFor("my_world", worldFolder)).isEqualTo(de.lazuli.api.cloudsync.WorldFreshnessHook.UpToDateStatus.UP_TO_DATE);
    }

    // -- FR-T.2: checkAndUploadStaleWorldsAtStartup --

    @Test
    void checkAndUploadStaleWorldsAtStartupSkipsAWorldAlreadyUpToDate(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Path file = worldFolder.resolve("level.dat");
        Files.writeString(file, "data");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000L));
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache,
                resolvedCloudWorldId("my_world"), "My World", "test-device", 5_000L);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        preferenceService.setSyncEnabled("my_world", true);
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.checkAndUploadStaleWorldsAtStartup(
                List.of(new WorldSaveSyncService.KnownWorld("my_world", worldFolder, "My World")));

        Mockito.verifyNoInteractions(worker);
    }

    @Test
    void checkAndUploadStaleWorldsAtStartupEnqueuesOnlyALocallyStaleWorld(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Path file = worldFolder.resolve("level.dat");
        Files.writeString(file, "data");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(10_000L));
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache,
                resolvedCloudWorldId("my_world"), "My World", "test-device", 5_000L);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        preferenceService.setSyncEnabled("my_world", true);
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                statusTracker, fakeMigrationService());

        service.checkAndUploadStaleWorldsAtStartup(
                List.of(new WorldSaveSyncService.KnownWorld("my_world", worldFolder, "My World")));

        Mockito.verify(worker).submitBackgroundWork(Mockito.any());
        assertThat(statusTracker.isUploadInProgress("my_world")).isTrue();
    }

    @Test
    void checkAndUploadStaleWorldsAtStartupDoesNotEnqueueForAnotherDevicesFingerprint(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Path file = worldFolder.resolve("level.dat");
        Files.writeString(file, "data");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(10_000L));
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache,
                "my_world", "My World", "other-device", 5_000L);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        preferenceService.setSyncEnabled("my_world", true);
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.checkAndUploadStaleWorldsAtStartup(
                List.of(new WorldSaveSyncService.KnownWorld("my_world", worldFolder, "My World")));

        Mockito.verifyNoInteractions(worker);
    }

    @Test
    void checkAndUploadStaleWorldsAtStartupDoesNotEnqueueForAnUnknownWorld(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        preferenceService.setSyncEnabled("my_world", true);
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.checkAndUploadStaleWorldsAtStartup(
                List.of(new WorldSaveSyncService.KnownWorld("my_world", worldFolder, "My World")));

        Mockito.verifyNoInteractions(worker);
    }

    // -- cloud-sync-conflict-ux: detailFor --

    @Test
    void detailForReturnsNullWhenNoFingerprintExists(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldSaveSyncService service = newServiceForFreshnessTests(tempDir, new WorldFingerprintCache());

        assertThat(service.detailFor("my_world", worldFolder.toAbsolutePath().toString(), "My World",
                "Survival", 1234L, false, de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch.unreadable()))
                .isNull();
    }

    @Test
    void detailForCarriesThroughPassedInPlatformSourcedFields(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 6_000L);

        WorldSaveSyncService service = newServiceForFreshnessTests(tempDir, fingerprintCache);

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch batch =
                new de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch(
                        42L, "Normal", true, 7L, "1.21.11", true, -1L, null, false);

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail detail =
                service.detailFor("my_world", worldFolder.toAbsolutePath().toString(), "My World",
                        "Survival", 9_999L, true, batch);

        assertThat(detail).isNotNull();
        assertThat(detail.local().gameMode()).isEqualTo("Survival");
        assertThat(detail.local().lastPlayedMillis()).isEqualTo(9_999L);
        assertThat(detail.local().hardcore()).isTrue();
        assertThat(detail.local().seed()).isEqualTo(42L);
        assertThat(detail.local().difficulty()).isEqualTo("Normal");
        assertThat(detail.local().cheatsEnabled()).isTrue();
        assertThat(detail.local().minecraftVersion()).isEqualTo("1.21.11");
        assertThat(detail.local().dayCount()).isEqualTo(7L);
        assertThat(detail.local().levelDatReadable()).isTrue();
        assertThat(detail.local().deviceLabel()).isEqualTo("test-device");
        assertThat(detail.cloud().displayName()).isEqualTo("My World");
        assertThat(detail.cloud().deviceLabel()).isEqualTo("other-device");
        assertThat(detail.cloud().syncedAtTimestamp()).isEqualTo(6_000L);
    }

    @Test
    void detailForAncestorSyncedAtTimestampIsNullWithNoAncestorEntry(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 6_000L);

        WorldSaveSyncService service = newServiceForFreshnessTests(tempDir, fingerprintCache);

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail detail =
                service.detailFor("my_world", worldFolder.toAbsolutePath().toString(), "My World",
                        "Survival", 0L, false, de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch.unreadable());

        assertThat(detail.local().ancestorSyncedAtTimestamp()).isNull();
    }

    @Test
    void detailForAncestorSyncedAtTimestampMatchesTheAncestorCache(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 6_000L);
        Path ancestorCachePath = tempDir.resolve("world-sync-ancestor-cache.json");
        WorldSyncAncestorCacheTestHelper.seed(ancestorCachePath, "my_world", "test-device", 3_000L);

        WorldSaveSyncService service = new WorldSaveSyncService(
                new FakeWorldArchiveCloudStore(), new FakeCloudFileStore(),
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                Mockito.mock(CloudSyncWorker.class), fingerprintCache, ancestorCachePath, "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail detail =
                service.detailFor("my_world", worldFolder.toAbsolutePath().toString(), "My World",
                        "Survival", 0L, false, de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch.unreadable());

        assertThat(detail.local().ancestorSyncedAtTimestamp()).isEqualTo(3_000L);
    }

    @Test
    void detailForCloudArchiveSizeMatchesFileSizeIncludingNotFoundCase(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 6_000L);
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put(WorldSaveSyncService.archiveFileName("my_world"), new byte[123]);

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, new FakeCloudFileStore(),
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                Mockito.mock(CloudSyncWorker.class), fingerprintCache,
                tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail detail =
                service.detailFor("my_world", worldFolder.toAbsolutePath().toString(), "My World",
                        "Survival", 0L, false, de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch.unreadable());
        assertThat(detail.cloud().archiveSizeBytes()).isEqualTo(123L);

        // Not-found case, passed through unchanged (-1).
        WorldFingerprintCache fingerprintCache2 = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache2, "other_world", "Other World", "other-device", 6_000L);
        Path otherWorldFolder = Files.createDirectory(tempDir.resolve("other_world"));
        WorldSaveSyncService service2 = new WorldSaveSyncService(
                new FakeWorldArchiveCloudStore(), new FakeCloudFileStore(),
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences-2.json"), w -> { }),
                Mockito.mock(CloudSyncWorker.class), fingerprintCache2,
                tempDir.resolve("world-sync-ancestor-cache-2.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());
        de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail detail2 =
                service2.detailFor("other_world", otherWorldFolder.toAbsolutePath().toString(), "Other World",
                        "Survival", 0L, false, de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch.unreadable());
        assertThat(detail2.cloud().archiveSizeBytes()).isEqualTo(-1L);
    }

    @Test
    void detailForRegionFileCountCountsMcaFiles(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");
        Path regionDir = Files.createDirectory(worldFolder.resolve("region"));
        Files.write(regionDir.resolve("r.0.0.mca"), new byte[1]);
        Files.write(regionDir.resolve("r.0.1.mca"), new byte[1]);
        Files.write(regionDir.resolve("not-a-region-file.txt"), new byte[1]);

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 6_000L);
        WorldSaveSyncService service = newServiceForFreshnessTests(tempDir, fingerprintCache);

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail detail =
                service.detailFor("my_world", worldFolder.toAbsolutePath().toString(), "My World",
                        "Survival", 0L, false, de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch.unreadable());

        assertThat(detail.local().regionFileCount()).isEqualTo(2);
    }

    @Test
    void detailForRegionFileCountIsNegativeOneWhenRegionDirDoesNotExist(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 6_000L);
        WorldSaveSyncService service = newServiceForFreshnessTests(tempDir, fingerprintCache);

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail detail =
                service.detailFor("my_world", worldFolder.toAbsolutePath().toString(), "My World",
                        "Survival", 0L, false, de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch.unreadable());

        assertThat(detail.local().regionFileCount()).isEqualTo(-1);
    }

    private WorldSaveSyncService newServiceForFreshnessTests(Path tempDir, WorldFingerprintCache fingerprintCache) {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);
        return new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());
    }

    /**
     * Small local helper seeding the RAM-only {@link WorldFingerprintCache}
     * directly (there is no on-disk fingerprint-cache file to write anymore
     * -- Cloud's current fingerprint state is process-memory-only, per the
     * no-on-disk-cloud-state-cache design; see {@link WorldFingerprintCache}'s
     * Javadoc).
     */
    private static final class WorldFingerprintCacheTestHelper {
        static void seed(WorldFingerprintCache cache, String worldSlug, String displayName, String deviceLabel, long syncedAtTimestamp) {
            List<WorldFingerprint> entries = new ArrayList<>(cache.entries());
            entries.add(new WorldFingerprint(worldSlug, displayName, deviceLabel, syncedAtTimestamp));
            cache.replaceAll(entries);
        }
    }

    private static final class WorldSyncAncestorCacheTestHelper {
        static void seed(Path path, String worldSlug, String deviceLabel, long syncedAtTimestamp) throws IOException {
            String json = "{\n  \"schemaVersion\": 1,\n  \"worlds\": [\n    { \"worldSlug\": \"" + worldSlug
                    + "\", \"deviceLabel\": \"" + deviceLabel
                    + "\", \"syncedAtTimestamp\": " + syncedAtTimestamp + " }\n  ]\n}\n";
            Files.writeString(path, json);
        }
    }

    @Test
    void successfulSyncWritesOwnAncestorEntryMatchingTheJustWrittenFingerprint(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "fake level data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.setSyncEnabled("my_world", true);
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        Path ancestorCachePath = tempDir.resolve("world-sync-ancestor-cache.json");

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), ancestorCachePath, "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.syncWorldNow("my_world", worldFolder, "My World");
        worker.pumpTickWork();

        assertThat(Files.readString(ancestorCachePath)).contains(resolvedCloudWorldId("my_world")).contains("test-device");
    }

    @Test
    void checkConflictForReturnsNoneWhenNoOwnAncestorEntryExists(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 2_000L);

        WorldSaveSyncService service = new WorldSaveSyncService(
                new FakeWorldArchiveCloudStore(), new FakeCloudFileStore(),
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                new CloudSyncWorker(w -> { }), fingerprintCache,
                tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        assertThat(service.checkConflictFor("my_world", worldFolder))
                .isEqualTo(de.lazuli.api.cloudsync.WorldConflictHook.ConflictStatus.NONE);
    }

    @Test
    void checkConflictForReturnsNoneWhenLocalUnchangedSinceOwnAncestor(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");
        Files.setLastModifiedTime(worldFolder.resolve("level.dat"), java.nio.file.attribute.FileTime.fromMillis(1_000L));

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        Path ancestorCachePath = tempDir.resolve("world-sync-ancestor-cache.json");
        WorldSyncAncestorCacheTestHelper.seed(ancestorCachePath, "my_world", "test-device", 5_000L);
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 6_000L);

        WorldSaveSyncService service = new WorldSaveSyncService(
                new FakeWorldArchiveCloudStore(), new FakeCloudFileStore(),
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                new CloudSyncWorker(w -> { }), fingerprintCache, ancestorCachePath, "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        assertThat(service.checkConflictFor("my_world", worldFolder))
                .isEqualTo(de.lazuli.api.cloudsync.WorldConflictHook.ConflictStatus.NONE);
    }

    @Test
    void checkConflictForReturnsNoneWhenLocalChangedButGlobalFingerprintStillMatchesOwnAncestor(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");
        Files.setLastModifiedTime(worldFolder.resolve("level.dat"), java.nio.file.attribute.FileTime.fromMillis(9_000L));

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        Path ancestorCachePath = tempDir.resolve("world-sync-ancestor-cache.json");
        WorldSyncAncestorCacheTestHelper.seed(ancestorCachePath, "my_world", "test-device", 5_000L);
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "test-device", 5_000L);

        WorldSaveSyncService service = new WorldSaveSyncService(
                new FakeWorldArchiveCloudStore(), new FakeCloudFileStore(),
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                new CloudSyncWorker(w -> { }), fingerprintCache, ancestorCachePath, "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        assertThat(service.checkConflictFor("my_world", worldFolder))
                .isEqualTo(de.lazuli.api.cloudsync.WorldConflictHook.ConflictStatus.NONE);
    }

    @Test
    void checkConflictForReturnsConflictWhenLocalAndCloudBothDivergedFromOwnAncestor(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");
        Files.setLastModifiedTime(worldFolder.resolve("level.dat"), java.nio.file.attribute.FileTime.fromMillis(9_000L));

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        Path ancestorCachePath = tempDir.resolve("world-sync-ancestor-cache.json");
        WorldSyncAncestorCacheTestHelper.seed(ancestorCachePath, "my_world", "test-device", 5_000L);
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 7_000L);

        WorldSaveSyncService service = new WorldSaveSyncService(
                new FakeWorldArchiveCloudStore(), new FakeCloudFileStore(),
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                new CloudSyncWorker(w -> { }), fingerprintCache, ancestorCachePath, "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        assertThat(service.checkConflictFor("my_world", worldFolder))
                .isEqualTo(de.lazuli.api.cloudsync.WorldConflictHook.ConflictStatus.CONFLICT);
    }

    /**
     * The exact repro this was fixed for: an external restore of a whole
     * backed-up run/config folder can bring back this device's own
     * internally-consistent-but-stale local ancestor record from *before*
     * this same device's own later upload(s) -- so the current global
     * fingerprint still carries this device's own {@code deviceLabel}, but
     * with a *different* {@code syncedAtTimestamp} than the (stale) restored
     * {@code ownAncestor}. This must be treated as a genuine "Cloud moved
     * since my last known sync" conflict, not silently reported as
     * up to date/synced -- see {@code checkConflictFor}'s Javadoc.
     */
    @Test
    void checkConflictForReturnsConflictWhenGlobalFingerprintChangedForThisDeviceButOwnAncestorIsStale(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");
        Files.setLastModifiedTime(worldFolder.resolve("level.dat"), java.nio.file.attribute.FileTime.fromMillis(9_000L));

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        Path ancestorCachePath = tempDir.resolve("world-sync-ancestor-cache.json");
        WorldSyncAncestorCacheTestHelper.seed(ancestorCachePath, "my_world", "test-device", 5_000L);
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "test-device", 8_000L);

        WorldSaveSyncService service = new WorldSaveSyncService(
                new FakeWorldArchiveCloudStore(), new FakeCloudFileStore(),
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                new CloudSyncWorker(w -> { }), fingerprintCache, ancestorCachePath, "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        assertThat(service.checkConflictFor("my_world", worldFolder))
                .isEqualTo(de.lazuli.api.cloudsync.WorldConflictHook.ConflictStatus.CONFLICT);
    }

    @Test
    void onWorldSavedNoOpsWhileConflictPending(@TempDir Path tempDir) {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.setSyncEnabled("my_world", true);
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();
        statusTracker.markConflictPending("my_world");

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, w -> { }, m -> { }, statusTracker, fakeMigrationService());

        service.onWorldSaved("my_world", tempDir, "My World");

        Mockito.verifyNoInteractions(worker);
    }

    @Test
    void checkAndUploadStaleWorldsAtStartupSkipsWorldsWithPendingConflict(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");
        Files.setLastModifiedTime(worldFolder.resolve("level.dat"), java.nio.file.attribute.FileTime.fromMillis(9_000L));

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "test-device", 1_000L);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.setSyncEnabled("my_world", true);
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();
        statusTracker.markConflictPending("my_world");

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, w -> { }, m -> { }, statusTracker, fakeMigrationService());

        service.checkAndUploadStaleWorldsAtStartup(
                List.of(new WorldSaveSyncService.KnownWorld("my_world", worldFolder, "My World")));

        Mockito.verifyNoInteractions(worker);
    }

    // -- Gap 2 (sync-conflict-coverage-gaps): handleSyncReenabled --

    @Test
    void handleSyncReenabledMarksConflictCheckPendingSynchronously(@TempDir Path tempDir) {
        Path worldFolder = tempDir.resolve("my_world");
        CloudSyncWorker worker = Mockito.mock(CloudSyncWorker.class);
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                new FakeWorldArchiveCloudStore(), new FakeCloudFileStore(),
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                worker, new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, w -> { }, m -> { }, statusTracker, fakeMigrationService());

        service.handleSyncReenabled("my_world", worldFolder, "My World");

        assertThat(statusTracker.isConflictCheckPending("my_world")).isTrue();
        Mockito.verify(worker).submitBackgroundWork(Mockito.any());
    }

    /**
     * Blocks until every {@code submitBackgroundWork} task queued on {@code
     * worker} so far has completed, by riding the worker's own single-thread
     * FIFO executor: a marker task submitted after the work under test is
     * guaranteed to run after it.
     */
    private static void awaitBackgroundWork(CloudSyncWorker worker) throws InterruptedException {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        worker.submitBackgroundWork(latch::countDown);
        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void handleSyncReenabledWithDivergentStateMarksConflictAndNeverUploads(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");
        Files.setLastModifiedTime(worldFolder.resolve("level.dat"), java.nio.file.attribute.FileTime.fromMillis(9_000L));

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        Path ancestorCachePath = tempDir.resolve("world-sync-ancestor-cache.json");
        WorldSyncAncestorCacheTestHelper.seed(ancestorCachePath, "my_world", "test-device", 5_000L);
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 7_000L);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, new FakeCloudFileStore(),
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                worker, fingerprintCache, ancestorCachePath, "test-device", 50, w -> { }, m -> { }, statusTracker, fakeMigrationService());

        service.handleSyncReenabled("my_world", worldFolder, "My World");
        awaitBackgroundWork(worker);
        worker.pumpTickWork();

        assertThat(statusTracker.hasPendingConflict("my_world")).isTrue();
        assertThat(statusTracker.isConflictCheckPending("my_world")).isFalse();
        assertThat(statusTracker.isUploadInProgress("my_world")).isFalse();
        assertThat(archiveStore.archives).isEmpty();
    }

    @Test
    void handleSyncReenabledWithNonDivergentStateProceedsWithNormalUploadFlow(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore,
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                worker, fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, w -> { }, m -> { }, statusTracker, fakeMigrationService());

        service.handleSyncReenabled("my_world", worldFolder, "My World");
        awaitBackgroundWork(worker);
        worker.pumpTickWork();

        assertThat(statusTracker.hasPendingConflict("my_world")).isFalse();
        assertThat(statusTracker.isConflictCheckPending("my_world")).isFalse();
        assertThat(archiveStore.archives).containsKey(cloudArchiveFileName("my_world"));
    }

    @Test
    void toggleOffThenOnTwiceWithNoExternalCloudChangeDoesNotFlagConflict(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore,
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                worker, fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, w -> { }, m -> { }, statusTracker, fakeMigrationService());

        // First toggle-on: no ancestor yet -> NONE -> normal upload, which
        // records this device's own ancestor entry.
        service.handleSyncReenabled("my_world", worldFolder, "My World");
        awaitBackgroundWork(worker);
        worker.pumpTickWork();
        assertThat(statusTracker.hasPendingConflict("my_world")).isFalse();

        // Second toggle-on (no external Cloud change in between): ancestor now
        // matches the global fingerprint this device itself just wrote ->
        // still NONE, no spurious conflict.
        service.handleSyncReenabled("my_world", worldFolder, "My World");
        awaitBackgroundWork(worker);
        worker.pumpTickWork();

        assertThat(statusTracker.hasPendingConflict("my_world")).isFalse();
    }

    // -- Request 3 (cloud-sync-threshold-and-full-sync-only): handleSyncDisabled --

    @Test
    void handleSyncDisabledOnSuccessRemovesFingerprintAndClearsStatus(@TempDir Path tempDir) throws InterruptedException {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-my_world.zip", new byte[10]);
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "test-device", 1_000L);
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();
        statusTracker.markSynced("my_world");

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore,
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                worker, fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, w -> { }, m -> { }, statusTracker, fakeMigrationService());

        service.handleSyncDisabled("my_world", "My World");
        awaitBackgroundWork(worker);

        assertThat(archiveStore.archives).doesNotContainKey("lazuli-world-my_world.zip");
        assertThat(fingerprintCache.entries()).noneMatch(entry -> entry.worldSlug().equals("my_world"));
        assertThat(statusTracker.statusFor("my_world")).isEqualTo(de.lazuli.api.cloudsync.WorldSyncStatusHook.SyncStatus.NOT_SYNCED);
    }

    @Test
    void handleSyncDisabledOnFailureLogsAndNotifiesWithoutTouchingFingerprint(@TempDir Path tempDir) throws InterruptedException {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.failDeleteWorldArchive = true;
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "test-device", 1_000L);
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        List<String> warnings = new ArrayList<>();
        List<String> notifications = new ArrayList<>();
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();
        statusTracker.markSynced("my_world");

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore,
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                worker, fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, warnings::add, notifications::add, statusTracker, fakeMigrationService());

        service.handleSyncDisabled("my_world", "My World");
        awaitBackgroundWork(worker);

        assertThat(warnings).anyMatch(message -> message.contains("My World"));
        assertThat(notifications).anyMatch(message -> message.contains("My World"));
        assertThat(fingerprintCache.entries()).anyMatch(entry -> entry.worldSlug().equals("my_world"));
        assertThat(statusTracker.statusFor("my_world")).isEqualTo(de.lazuli.api.cloudsync.WorldSyncStatusHook.SyncStatus.SYNCED);
    }

    @Test
    void handleSyncDisabledOnSuccessAlsoDeletesTheCloudMetadataFile(@TempDir Path tempDir) throws InterruptedException {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-my_world.zip", new byte[10]);
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        cloudFileStore.files.put(WorldSaveSyncService.metadataFileName("my_world"), new byte[5]);
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "test-device", 1_000L);
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore,
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                worker, fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, w -> { }, m -> { }, statusTracker, fakeMigrationService());

        service.handleSyncDisabled("my_world", "My World");
        awaitBackgroundWork(worker);

        assertThat(cloudFileStore.deletedFileNames).contains(WorldSaveSyncService.metadataFileName("my_world"));
        assertThat(cloudFileStore.files).doesNotContainKey(WorldSaveSyncService.metadataFileName("my_world"));
    }

    @Test
    void handleSyncDisabledMetadataDeleteFailureDoesNotBlockTheRestOfSuccessCleanup(@TempDir Path tempDir) throws InterruptedException {
        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        archiveStore.archives.put("lazuli-world-my_world.zip", new byte[10]);
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        cloudFileStore.failDelete = true;
        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "test-device", 1_000L);
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });
        List<String> warnings = new ArrayList<>();
        List<String> notifications = new ArrayList<>();
        WorldSyncStatusTracker statusTracker = new WorldSyncStatusTracker();
        statusTracker.markSynced("my_world");

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore,
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                worker, fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, warnings::add, notifications::add, statusTracker, fakeMigrationService());

        service.handleSyncDisabled("my_world", "My World");
        awaitBackgroundWork(worker);

        // The archive delete already succeeded (this method's primary success signal) --
        // a metadata-delete failure is logged only, not surfaced as a second
        // player-facing notification, and does not prevent the rest of the
        // success-path cleanup from having already completed.
        assertThat(warnings).anyMatch(message -> message.contains("metadata"));
        assertThat(notifications).noneMatch(message -> message.contains("metadata"));
        assertThat(fingerprintCache.entries()).noneMatch(entry -> entry.worldSlug().equals("my_world"));
        assertThat(statusTracker.statusFor("my_world")).isEqualTo(de.lazuli.api.cloudsync.WorldSyncStatusHook.SyncStatus.NOT_SYNCED);
    }

    // -- cloud-world-metadata-file spec --

    @Test
    void syncWorldNowUploadsAMetadataFileOnASuccessfulWholeArchiveSync(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "fake level data");

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });

        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 50, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch batch =
                new de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch(
                        42L, "Normal", false, 3L, "1.21.11", true, -1L, null, false);

        service.syncWorldNow("my_world", worldFolder, "My World", batch);
        worker.pumpTickWork();

        assertThat(cloudFileStore.files).containsKey(cloudMetadataFileName("my_world"));
        Optional<de.lazuli.features.steamcloudsync.api.WorldCloudMetadata> metadata = service.cloudMetadataFor("my_world");
        assertThat(metadata).isPresent();
        assertThat(metadata.get().worldSlug()).isEqualTo(resolvedCloudWorldId("my_world"));
        assertThat(metadata.get().minecraftVersion()).isEqualTo("1.21.11");
        assertThat(metadata.get().seed()).isEqualTo(42L);
        assertThat(metadata.get().difficulty()).isEqualTo("Normal");
        assertThat(metadata.get().contentSignature()).isNotBlank();
    }

    @Test
    void syncWorldNowUploadsAMetadataFileEvenWhenTheArchiveIsSkippedForBeingTooLarge(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("big_world"));
        Files.write(worldFolder.resolve("region.dat"), new byte[2048]);

        FakeWorldArchiveCloudStore archiveStore = new FakeWorldArchiveCloudStore();
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSyncPreferenceService preferenceService =
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { });
        preferenceService.load();
        CloudSyncWorker worker = new CloudSyncWorker(w -> { });

        // maxWorldArchiveSizeMb=0 -> even this tiny world is "over threshold" -> SKIPPED.
        WorldSaveSyncService service = new WorldSaveSyncService(
                archiveStore, cloudFileStore, preferenceService, worker,
                new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"), "test-device", 0, w -> { }, m -> { },
                new WorldSyncStatusTracker(), fakeMigrationService());

        service.syncWorldNow("big_world", worldFolder, "Big World");
        worker.pumpTickWork();

        assertThat(archiveStore.archives).doesNotContainKey(cloudArchiveFileName("big_world"));
        // Requirement 3: the metadata file uploads even though the archive itself was SKIPPED.
        assertThat(cloudFileStore.files).containsKey(cloudMetadataFileName("big_world"));
        Optional<de.lazuli.features.steamcloudsync.api.WorldCloudMetadata> metadata = service.cloudMetadataFor("big_world");
        assertThat(metadata).isPresent();
        // Resolved decision: contentSignature is computed unconditionally, even for a
        // SKIPPED/over-threshold world -- never narrowed to WHOLE_ARCHIVE-only worlds.
        assertThat(metadata.get().contentSignature()).isNotBlank();
    }

    @Test
    void cloudMetadataForIsEmptyWhenNoMetadataFileExists(@TempDir Path tempDir) {
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        WorldSaveSyncService service = new WorldSaveSyncService(
                new FakeWorldArchiveCloudStore(), cloudFileStore,
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                new CloudSyncWorker(w -> { }), new WorldFingerprintCache(), tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, w -> { }, m -> { }, new WorldSyncStatusTracker(), fakeMigrationService());

        assertThat(service.cloudMetadataFor("never_synced_world")).isEmpty();
    }

    @Test
    void detailForCloudFieldsAreSourcedFromTheMetadataFileWhenPresent(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 6_000L);
        FakeCloudFileStore cloudFileStore = new FakeCloudFileStore();
        de.lazuli.features.steamcloudsync.config.WorldCloudMetadataIO metadataIO =
                new de.lazuli.features.steamcloudsync.config.WorldCloudMetadataIO();
        de.lazuli.features.steamcloudsync.api.WorldCloudMetadata metadata =
                new de.lazuli.features.steamcloudsync.api.WorldCloudMetadata(
                        de.lazuli.features.steamcloudsync.config.WorldCloudMetadataIO.CURRENT_SCHEMA_VERSION,
                        "my_world", "My World", 4_000L, "1.21.11", 99L, "Creative", "Peaceful", true, "deadbeef", 6_000L, null);
        cloudFileStore.files.put(WorldSaveSyncService.metadataFileName("my_world"),
                metadataIO.serialize(metadata).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        WorldSaveSyncService service = new WorldSaveSyncService(
                new FakeWorldArchiveCloudStore(), cloudFileStore,
                new WorldSyncPreferenceService(tempDir.resolve("world-sync-preferences.json"), w -> { }),
                Mockito.mock(CloudSyncWorker.class), fingerprintCache, tempDir.resolve("world-sync-ancestor-cache.json"),
                "test-device", 50, w -> { }, m -> { }, new WorldSyncStatusTracker(), fakeMigrationService());

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail detail =
                service.detailFor("my_world", worldFolder.toAbsolutePath().toString(), "My World",
                        "Survival", 0L, false, de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch.unreadable());

        assertThat(detail.cloud().lastPlayedMillis()).isEqualTo(4_000L);
        assertThat(detail.cloud().minecraftVersion()).isEqualTo("1.21.11");
        assertThat(detail.cloud().seed()).isEqualTo(99L);
        assertThat(detail.cloud().gameMode()).isEqualTo("Creative");
        assertThat(detail.cloud().difficulty()).isEqualTo("Peaceful");
        assertThat(detail.cloud().hardcore()).isTrue();
        assertThat(detail.cloud().contentSignature()).isEqualTo("deadbeef");
    }

    @Test
    void detailForCloudFieldsFallBackToSentinelsWhenNoMetadataFileExists(@TempDir Path tempDir) throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "data");

        WorldFingerprintCache fingerprintCache = new WorldFingerprintCache();
        WorldFingerprintCacheTestHelper.seed(fingerprintCache, "my_world", "My World", "other-device", 6_000L);

        WorldSaveSyncService service = newServiceForFreshnessTests(tempDir, fingerprintCache);

        de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail detail =
                service.detailFor("my_world", worldFolder.toAbsolutePath().toString(), "My World",
                        "Survival", 0L, false, de.lazuli.api.cloudsync.WorldConflictResolutionHook.LevelDatBatch.unreadable());

        assertThat(detail).isNotNull();
        assertThat(detail.cloud().displayName()).isEqualTo("My World");
        assertThat(detail.cloud().syncedAtTimestamp()).isEqualTo(6_000L);
        assertThat(detail.cloud().contentSignature()).isNull();
        assertThat(detail.cloud().minecraftVersion()).isNull();
        assertThat(detail.cloud().seed()).isNull();
    }

    @Test
    void computeContentSignatureIsDeterministicAndChangesWhenContentChanges(@TempDir Path tempDir) throws Exception {
        java.lang.reflect.Method method = WorldSaveSyncService.class.getDeclaredMethod("computeContentSignature", Path.class);
        method.setAccessible(true);

        Path worldFolder = Files.createDirectory(tempDir.resolve("my_world"));
        Files.writeString(worldFolder.resolve("level.dat"), "original content");

        String first = (String) method.invoke(null, worldFolder);
        String second = (String) method.invoke(null, worldFolder);
        assertThat(first).isEqualTo(second);

        Files.writeString(worldFolder.resolve("level.dat"), "changed content");
        String third = (String) method.invoke(null, worldFolder);
        assertThat(third).isNotEqualTo(first);
    }
}
