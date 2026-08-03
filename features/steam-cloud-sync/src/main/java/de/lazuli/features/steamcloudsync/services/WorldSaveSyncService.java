package de.lazuli.features.steamcloudsync.services;

import de.lazuli.features.steamcloudsync.api.WorldFingerprint;
import de.lazuli.features.steamcloudsync.config.WorldFingerprintIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Group 6's world-save-sync business logic: the size check and
 * whole-archive-vs-selective-fallback decision (FR6.3-FR6.5), archive
 * building via {@code java.util.zip} (no new dependency), the fingerprint
 * conflict warning (FR6.6), and quota/{@code fileForget} bookkeeping (FR6.7).
 * Consults {@link WorldSyncPreferenceService} before acting on a given world
 * (FR6.2).
 *
 * <p>{@link #decideStrategy(long, int, boolean)} and
 * {@link #computeFolderSizeBytes(Path)} are pure/plain-JVM-testable (the
 * former needs no I/O at all; the latter only real, non-Steam
 * {@code java.nio.file} calls, testable with a {@code @TempDir}, mirroring
 * {@code HelloWorldMainMenuConfigIOTest}'s own precedent). Every Cloud call
 * goes through the injected {@link WorldArchiveCloudStore}/
 * {@link CloudFileStore} seams -- never {@code com.codedisaster.steamworks.*}
 * directly (NFR1).
 *
 * <p>Usage example (from the platform composition root, wired to the FR6.2
 * world-unload checkpoint):
 * <pre>{@code
 * service.onWorldUnload("my_world_folder", worldFolderPath, "My World");
 * }</pre>
 */
public final class WorldSaveSyncService {

    /**
     * Files/directories included in the selective critical-files fallback
     * archive (FR6.4) -- restores identity/progress/game-rules context, but
     * never terrain.
     */
    public static final List<String> SELECTIVE_FALLBACK_ENTRIES =
            List.of("level.dat", "playerdata", "stats", "advancements", "icon.png");

    private static final String FINGERPRINT_CLOUD_FILE_NAME = "lazuli-world-fingerprints.json";

    /** The whole-archive-vs-selective-fallback-vs-skip decision (FR6.3-FR6.5). */
    public enum SyncStrategy {
        WHOLE_ARCHIVE,
        SELECTIVE_FALLBACK,
        SKIPPED
    }

    private final WorldArchiveCloudStore archiveStore;
    private final CloudFileStore cloudFileStore;
    private final WorldSyncPreferenceService preferenceService;
    private final CloudSyncWorker worker;
    private final Path fingerprintCachePath;
    private final String deviceLabel;
    private final int maxWorldArchiveSizeMb;
    private final boolean allowSelectiveFallback;
    private final Consumer<String> warningLogger;
    private final Consumer<String> playerNotifier;
    private final WorldSyncStatusTracker statusTracker;
    private final WorldFingerprintIO fingerprintIO = new WorldFingerprintIO();

    /**
     * @param archiveStore           the Group 6 Cloud seam (real or no-op)
     * @param cloudFileStore         the small-file Cloud seam, used only for
     *                               the fingerprint metadata file
     * @param preferenceService      the per-world sync-preference service
     *                               (FR6.1/FR6.2)
     * @param worker                 hops compression/archive-building onto a
     *                               background thread and the actual Steam
     *                               calls back onto the client tick thread
     * @param fingerprintCachePath   this device's local cache of the Cloud
     *                               fingerprint file
     * @param deviceLabel            this device's own label (see
     *                               {@link DeviceLabelResolver})
     * @param maxWorldArchiveSizeMb  the size threshold (FR6.3), from
     *                               {@code SteamCloudSyncConfig}
     * @param allowSelectiveFallback whether to fall back to the critical-files
     *                               archive for an over-threshold world
     *                               (FR6.4), from {@code SteamCloudSyncConfig}
     * @param warningLogger          receives a human-readable message for any
     *                               internal failure; never invoked with a
     *                               thrown exception
     * @param playerNotifier         receives a human-readable,
     *                               player-visible message for the FR6.4/
     *                               FR6.6/FR6.7 notifications this service
     *                               must surface
     * @param statusTracker          receives per-world sync-status updates at
     *                               the same checkpoints
     *                               {@code warningLogger}/{@code playerNotifier}
     *                               are already called (FRU.3 of the
     *                               diagnostics/UI/compression companion spec)
     */
    public WorldSaveSyncService(
            WorldArchiveCloudStore archiveStore,
            CloudFileStore cloudFileStore,
            WorldSyncPreferenceService preferenceService,
            CloudSyncWorker worker,
            Path fingerprintCachePath,
            String deviceLabel,
            int maxWorldArchiveSizeMb,
            boolean allowSelectiveFallback,
            Consumer<String> warningLogger,
            Consumer<String> playerNotifier,
            WorldSyncStatusTracker statusTracker) {
        this.archiveStore = Objects.requireNonNull(archiveStore, "archiveStore");
        this.cloudFileStore = Objects.requireNonNull(cloudFileStore, "cloudFileStore");
        this.preferenceService = Objects.requireNonNull(preferenceService, "preferenceService");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.fingerprintCachePath = Objects.requireNonNull(fingerprintCachePath, "fingerprintCachePath");
        this.deviceLabel = Objects.requireNonNull(deviceLabel, "deviceLabel");
        this.maxWorldArchiveSizeMb = maxWorldArchiveSizeMb;
        this.allowSelectiveFallback = allowSelectiveFallback;
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.playerNotifier = Objects.requireNonNull(playerNotifier, "playerNotifier");
        this.statusTracker = Objects.requireNonNull(statusTracker, "statusTracker");
    }

    /**
     * The FR6.2 trigger: called on world unload/exit. A no-op unless this
     * world's per-world preference is enabled. Archive building runs on the
     * background thread; only the final Steam call hops back onto the
     * client tick thread (Architecture -- Threading).
     *
     * @param worldSlug   the world's save-folder name
     * @param worldFolder the world's on-disk save folder
     * @param displayName a player-facing name for the world
     */
    public void onWorldUnload(String worldSlug, Path worldFolder, String displayName) {
        if (!preferenceService.isSyncEnabled(worldSlug)) {
            return;
        }
        worker.submitBackgroundWork(() -> syncWorldNow(worldSlug, worldFolder, displayName));
    }

    /**
     * Pulls the Cloud fingerprint file into the local cache. Call once at
     * the client-startup checkpoint (FR0.3), the same as every other Cloud
     * file -- this is what makes cloud-only-world detection (FR6.8) cheap
     * and synchronous later, since it only ever re-reads the already-local
     * cache.
     */
    public void pullFingerprintsAtStartup() {
        cloudFileStore.read(FINGERPRINT_CLOUD_FILE_NAME).ifPresent(bytes -> {
            WorldFingerprintIO.ParseResult result = fingerprintIO.parse(new String(bytes, StandardCharsets.UTF_8));
            if (result.warning() != null) {
                warningLogger.accept(result.warning());
            }
            writeFingerprintCache(result.entries());
        });
    }

    /**
     * The size-threshold decision (FR6.3-FR6.5): a world at or under the
     * threshold always uses the whole archive; only an over-threshold world
     * ever considers selective fallback, and only if allowed; otherwise the
     * world is skipped entirely this checkpoint.
     *
     * @param folderSizeBytes        the world folder's total on-disk size
     * @param maxWorldArchiveSizeMb  the configured threshold, in megabytes
     * @param allowSelectiveFallback whether selective fallback is allowed
     * @return the strategy to use
     */
    public static SyncStrategy decideStrategy(long folderSizeBytes, int maxWorldArchiveSizeMb, boolean allowSelectiveFallback) {
        long maxBytes = maxWorldArchiveSizeMb * 1024L * 1024L;
        if (folderSizeBytes <= maxBytes) {
            return SyncStrategy.WHOLE_ARCHIVE;
        }
        return allowSelectiveFallback ? SyncStrategy.SELECTIVE_FALLBACK : SyncStrategy.SKIPPED;
    }

    /**
     * Computes a world folder's total on-disk size (FR6.3).
     *
     * @param worldFolder the world's on-disk save folder
     * @return the total size, in bytes, of every regular file under
     *         {@code worldFolder}
     * @throws IOException if the folder cannot be walked
     */
    public static long computeFolderSizeBytes(Path worldFolder) throws IOException {
        try (Stream<Path> stream = Files.walk(worldFolder)) {
            long total = 0L;
            for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                total += Files.size(path);
            }
            return total;
        }
    }

    /**
     * @param worldSlug the world's save-folder name
     * @return the flat, lowercase Cloud file name this world's archive is
     *         stored under
     */
    public static String archiveFileName(String worldSlug) {
        return "lazuli-world-" + worldSlug + ".zip";
    }

    /**
     * The actual size-check/archive-build/fingerprint/quota orchestration,
     * package-private so tests can invoke it synchronously (bypassing the
     * {@link CloudSyncWorker} background-thread hop {@link #onWorldUnload}
     * uses in production) rather than racing a real background thread.
     */
    void syncWorldNow(String worldSlug, Path worldFolder, String displayName) {
        try {
            long sizeBytes = computeFolderSizeBytes(worldFolder);
            SyncStrategy strategy = decideStrategy(sizeBytes, maxWorldArchiveSizeMb, allowSelectiveFallback);

            if (strategy == SyncStrategy.SKIPPED) {
                playerNotifier.accept("World \"" + displayName + "\" (" + formatMb(sizeBytes) + " MB) exceeds the "
                        + maxWorldArchiveSizeMb + " MB Cloud sync threshold; not synced this session.");
                statusTracker.markSkippedTooLarge(worldSlug);
                return;
            }

            byte[] archiveBytes = strategy == SyncStrategy.WHOLE_ARCHIVE
                    ? buildWholeArchive(worldFolder)
                    : buildSelectiveArchive(worldFolder);

            if (strategy == SyncStrategy.SELECTIVE_FALLBACK) {
                playerNotifier.accept("World \"" + displayName + "\" (" + formatMb(sizeBytes) + " MB) exceeds the "
                        + maxWorldArchiveSizeMb + " MB Cloud sync threshold; syncing a reduced critical-files-only copy "
                        + "(no terrain) instead of the full world.");
            }

            checkFingerprintForConflict(worldSlug, displayName);
            ensureQuota(archiveBytes.length, worldSlug);

            String archiveFileName = archiveFileName(worldSlug);
            playerNotifier.accept("Uploading world \"" + displayName + "\" (" + archiveBytes.length + " bytes) to Steam Cloud.");
            worker.enqueueTickThreadWork(() -> {
                boolean written = archiveStore.streamWrite(archiveFileName, archiveBytes);
                if (written) {
                    updateFingerprint(worldSlug, displayName);
                    statusTracker.markSynced(worldSlug);
                    playerNotifier.accept("Uploaded world \"" + displayName + "\" to Steam Cloud.");
                } else {
                    String message = "Failed to sync world \"" + displayName + "\" (" + archiveBytes.length
                            + " bytes) to Steam Cloud; see the preceding Steam Cloud log line for the specific cause.";
                    warningLogger.accept(message);
                    statusTracker.markError(worldSlug, message);
                }
            });
        } catch (IOException e) {
            warningLogger.accept("Failed to build Cloud archive for world \"" + displayName + "\": " + e);
            statusTracker.markError(worldSlug, e.getMessage());
        }
    }

    private byte[] buildWholeArchive(Path worldFolder) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            try (Stream<Path> stream = Files.walk(worldFolder)) {
                for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    addZipEntry(zip, worldFolder, path);
                }
            }
        }
        return buffer.toByteArray();
    }

    private byte[] buildSelectiveArchive(Path worldFolder) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (String entryName : SELECTIVE_FALLBACK_ENTRIES) {
                Path entryPath = worldFolder.resolve(entryName);
                if (!Files.exists(entryPath)) {
                    continue;
                }
                if (Files.isDirectory(entryPath)) {
                    try (Stream<Path> stream = Files.walk(entryPath)) {
                        for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                            addZipEntry(zip, worldFolder, path);
                        }
                    }
                } else {
                    addZipEntry(zip, worldFolder, entryPath);
                }
            }
        }
        return buffer.toByteArray();
    }

    private void addZipEntry(ZipOutputStream zip, Path root, Path file) throws IOException {
        String entryName = root.relativize(file).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private void checkFingerprintForConflict(String worldSlug, String displayName) {
        readLocalFingerprintCache().stream()
                .filter(fingerprint -> fingerprint.worldSlug().equals(worldSlug))
                .findFirst()
                .ifPresent(existing -> {
                    if (!existing.deviceLabel().equals(deviceLabel)) {
                        playerNotifier.accept("World \"" + displayName + "\" was last synced from \""
                                + existing.deviceLabel() + "\" at " + Instant.ofEpochMilli(existing.syncedAtTimestamp())
                                + "; continuing will overwrite that copy.");
                    }
                });
    }

    private void ensureQuota(int neededBytes, String worldSlugBeingWritten) {
        long[] total = new long[1];
        long[] available = new long[1];
        if (!archiveStore.getQuota(total, available)) {
            warningLogger.accept("Steam Cloud quota check failed for world \"" + worldSlugBeingWritten
                    + "\" (Steam getQuota() call did not succeed); proceeding without a quota pre-check.");
            return;
        }
        if (available[0] >= neededBytes) {
            return;
        }

        List<WorldFingerprint> candidates = new ArrayList<>(readLocalFingerprintCache());
        candidates.removeIf(fingerprint -> fingerprint.worldSlug().equals(worldSlugBeingWritten));
        candidates.sort(Comparator.comparingLong(WorldFingerprint::syncedAtTimestamp));

        int evicted = 0;
        for (WorldFingerprint candidate : candidates) {
            if (available[0] >= neededBytes) {
                break;
            }
            if (archiveStore.forget(archiveFileName(candidate.worldSlug()))) {
                evicted++;
                playerNotifier.accept("World \"" + candidate.displayName()
                        + "\" is no longer Cloud-backed (still fully playable locally) -- "
                        + "Cloud quota was needed for another world.");
                archiveStore.getQuota(total, available);
            }
        }

        if (available[0] < neededBytes) {
            warningLogger.accept("Cloud quota still insufficient for world \"" + worldSlugBeingWritten
                    + "\" after evicting " + evicted + " older world(s): need " + neededBytes + " bytes, have "
                    + available[0] + " of " + total[0] + " bytes total. The write that follows is expected to fail.");
        }
    }

    private void updateFingerprint(String worldSlug, String displayName) {
        List<WorldFingerprint> fingerprints = new ArrayList<>(readLocalFingerprintCache());
        fingerprints.removeIf(fingerprint -> fingerprint.worldSlug().equals(worldSlug));
        fingerprints.add(new WorldFingerprint(worldSlug, displayName, deviceLabel, System.currentTimeMillis()));
        writeFingerprintCache(fingerprints);
        cloudFileStore.write(FINGERPRINT_CLOUD_FILE_NAME, fingerprintIO.serialize(fingerprints).getBytes(StandardCharsets.UTF_8));
    }

    private List<WorldFingerprint> readLocalFingerprintCache() {
        try {
            if (Files.notExists(fingerprintCachePath)) {
                return List.of();
            }
            WorldFingerprintIO.ParseResult result = fingerprintIO.parse(Files.readString(fingerprintCachePath, StandardCharsets.UTF_8));
            if (result.warning() != null) {
                warningLogger.accept(result.warning());
            }
            return result.entries();
        } catch (IOException e) {
            warningLogger.accept("Failed to read " + fingerprintCachePath + ": " + e);
            return List.of();
        }
    }

    private void writeFingerprintCache(List<WorldFingerprint> fingerprints) {
        try {
            Path parent = fingerprintCachePath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(fingerprintCachePath, fingerprintIO.serialize(fingerprints), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warningLogger.accept("Failed to write " + fingerprintCachePath + ": " + e);
        }
    }

    private static String formatMb(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }
}
