package de.lazuli.features.steamcloudsync.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * Shared implementation of the FR0.4 reconciliation rule -- "closest we can
 * get to last-write-wins" between a local file and its Cloud counterpart:
 * compares {@code CloudFileStore.fileTimestamp(cloudFileName)} against the
 * local file's own last-modified time, and copies whichever side is newer
 * over the other.
 *
 * <p>Package-private: used internally by {@code BookmarkedServersService},
 * {@code NotesService}, and {@code LastPlayedPointerService} (Groups 3-5,
 * each of which owns a real local file this reconciliation logic can compare
 * against). Group 6's cloud fingerprint metadata file (FR6.6/FR6.8) also
 * reconciles through this same helper, from {@code WorldSaveSyncService}.
 * Groups 1/2 ({@code CloudSyncable}) do <em>not</em> use this helper -- see
 * {@code CloudSyncCoordinator}'s own JavaDoc for why that contract's
 * reconciliation is necessarily shaped differently (no local file path is
 * ever exposed through {@code CloudSyncable} itself).
 */
final class LocalCloudFileReconciler {

    private LocalCloudFileReconciler() {
    }

    /**
     * Reconciles {@code localFilePath} against {@code cloudFileName}: if the
     * Cloud copy is strictly newer than the local file's last-modified time
     * (or the local file does not exist), the Cloud copy is pulled down over
     * the local file. Otherwise (local is newer, equal, or Cloud has no
     * copy yet), the local file's current bytes are pushed up to Cloud. A
     * no-op if {@code syncEnabled} is {@code false} (the owning group's
     * config toggle is off) -- neither direction is touched.
     *
     * @param cloudFileStore the Cloud seam to read/write through
     * @param cloudFileName  the flat, lowercase Cloud file name
     * @param localFilePath  the local file's location
     * @param syncEnabled    whether this group's Cloud sync is currently
     *                       enabled at all
     * @param warningLogger  receives a human-readable message for any local
     *                       I/O failure encountered; never invoked with a
     *                       thrown exception
     */
    static void reconcile(
            CloudFileStore cloudFileStore,
            String cloudFileName,
            Path localFilePath,
            boolean syncEnabled,
            Consumer<String> warningLogger) {
        if (!syncEnabled) {
            return;
        }

        OptionalLong cloudTimestamp = cloudFileStore.fileTimestamp(cloudFileName);
        long localTimestamp = localLastModifiedMillis(localFilePath);

        if (cloudTimestamp.isPresent() && cloudTimestamp.getAsLong() > localTimestamp) {
            cloudFileStore.read(cloudFileName).ifPresent(bytes -> writeLocal(localFilePath, bytes, warningLogger));
        } else if (localTimestamp >= 0) {
            try {
                byte[] localBytes = Files.readAllBytes(localFilePath);
                cloudFileStore.write(cloudFileName, localBytes);
            } catch (IOException e) {
                warningLogger.accept("Failed to read " + localFilePath + " for Cloud reconciliation: " + e);
            }
        }
    }

    private static long localLastModifiedMillis(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    private static void writeLocal(Path path, byte[] bytes, Consumer<String> warningLogger) {
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, bytes);
        } catch (IOException e) {
            warningLogger.accept("Failed to write " + path + " during Cloud reconciliation: " + e);
        }
    }
}
