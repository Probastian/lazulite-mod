package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.CloudSyncable;

import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * The FR0.4 "closest we can get to last-write-wins" reconciliation rule,
 * applied to a {@link CloudSyncable} (Groups 1-2): compares
 * {@code CloudFileStore.fileTimestamp(cloudFileName)} against
 * {@link CloudSyncable#localLastModifiedMillis()}, and copies whichever side
 * is newer over the other -- the same rule {@link LocalCloudFileReconciler}
 * already applies to Groups 3-5's plain local files, adapted to a contract
 * that reports its own timestamp instead of exposing a
 * {@link java.nio.file.Path}.
 *
 * <p>Package-private: used internally by {@link CloudSyncCoordinator}.
 *
 * <p>Usage example:
 * <pre>{@code
 * CloudSyncableReconciler.reconcileAtStartup(cloudFileStore, "lazuli-cloudsync-my-feature.dat",
 *         syncable, true, LazuliMod.LOGGER::warn);
 * // ... later, at client shutdown:
 * CloudSyncableReconciler.pushOnShutdown(cloudFileStore, "lazuli-cloudsync-my-feature.dat",
 *         syncable, true, LazuliMod.LOGGER::warn);
 * }</pre>
 */
final class CloudSyncableReconciler {

    private CloudSyncableReconciler() {
    }

    /**
     * Reconciles {@code syncable} against its Cloud copy: if the Cloud copy
     * is strictly newer than {@link CloudSyncable#localLastModifiedMillis()},
     * the Cloud copy is imported. Otherwise (local is newer, equal, or Cloud
     * has no copy yet, and local state actually exists), the local state is
     * pushed to Cloud. A no-op if {@code syncEnabled} is {@code false} --
     * neither direction is touched.
     *
     * @param cloudFileStore the Cloud seam to read/write through
     * @param cloudFileName  the flat, lowercase Cloud file name
     * @param syncable       the syncable to reconcile
     * @param syncEnabled    whether this group's Cloud sync is currently
     *                       enabled at all
     * @param warningLogger  receives a human-readable message for any
     *                       failure; never invoked with a thrown exception
     */
    static void reconcileAtStartup(
            CloudFileStore cloudFileStore,
            String cloudFileName,
            CloudSyncable syncable,
            boolean syncEnabled,
            Consumer<String> warningLogger) {
        if (!syncEnabled) {
            return;
        }

        OptionalLong cloudTimestamp = cloudFileStore.fileTimestamp(cloudFileName);
        long localTimestamp = safeLocalTimestamp(syncable, warningLogger);

        if (cloudTimestamp.isPresent() && cloudTimestamp.getAsLong() > localTimestamp) {
            cloudFileStore.read(cloudFileName).ifPresent(bytes -> importSafely(syncable, bytes, warningLogger));
        } else if (localTimestamp >= 0) {
            exportSafely(cloudFileStore, cloudFileName, syncable, warningLogger);
        }
    }

    /**
     * Pushes {@code syncable}'s current state to Cloud unconditionally,
     * capturing whatever this session accumulated (FR1.3/FR1.4). Call once
     * at the client-shutdown checkpoint. A no-op if {@code syncEnabled} is
     * {@code false}.
     *
     * @param cloudFileStore the Cloud seam to write through
     * @param cloudFileName  the flat, lowercase Cloud file name
     * @param syncable       the syncable to push
     * @param syncEnabled    whether this group's Cloud sync is currently
     *                       enabled at all
     * @param warningLogger  receives a human-readable message for any
     *                       failure; never invoked with a thrown exception
     */
    static void pushOnShutdown(
            CloudFileStore cloudFileStore,
            String cloudFileName,
            CloudSyncable syncable,
            boolean syncEnabled,
            Consumer<String> warningLogger) {
        if (!syncEnabled) {
            return;
        }
        exportSafely(cloudFileStore, cloudFileName, syncable, warningLogger);
    }

    private static long safeLocalTimestamp(CloudSyncable syncable, Consumer<String> warningLogger) {
        try {
            return syncable.localLastModifiedMillis();
        } catch (RuntimeException e) {
            warningLogger.accept("Failed to read local last-modified time for \"" + syncable.cloudSyncId() + "\": " + e);
            return -1L;
        }
    }

    private static void importSafely(CloudSyncable syncable, byte[] bytes, Consumer<String> warningLogger) {
        try {
            syncable.importState(bytes);
        } catch (RuntimeException e) {
            warningLogger.accept("Failed to import Cloud state for \"" + syncable.cloudSyncId() + "\": " + e);
        }
    }

    private static void exportSafely(
            CloudFileStore cloudFileStore, String cloudFileName, CloudSyncable syncable, Consumer<String> warningLogger) {
        try {
            cloudFileStore.write(cloudFileName, syncable.exportState());
        } catch (RuntimeException e) {
            warningLogger.accept("Failed to export Cloud state for \"" + syncable.cloudSyncId() + "\": " + e);
        }
    }
}
