package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.DownloadProgressPresenter;
import de.lazuli.api.cloudsync.RestoreHandle;
import de.lazuli.api.cloudsync.RestoreProgress;
import de.lazuli.api.cloudsync.RestoreProgressListener;
import de.lazuli.api.cloudsync.StaleSaveFolderHealer;
import de.lazuli.api.cloudsync.WorldRestoreHook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Implements {@link WorldRestoreHook}: the Steam-calling half of restoring a
 * cloud-only world (FR6.10-FR6.13). Reads the archive's Cloud file (a fast,
 * already-local read per FR6.11 -- Steam Cloud fully downloads a user's files
 * before the game launches), extracts it into a staging directory, and
 * atomically moves it into place as a new, real world folder only once every
 * entry has extracted without error (FR6.12).
 *
 * <p>{@link #beginRestore(String, String, RestoreProgressListener)} performs
 * the FR6.13 same-slug collision check synchronously (cheap) and kicks off
 * the Cloud read on the calling thread (Minecraft's client thread, per this
 * project's single-thread Steamworks convention -- cheap, same as any other
 * small Cloud call, Architecture -- Threading); decompression/extraction is
 * handed off to {@link CloudSyncWorker}'s background thread once every chunk
 * has been read, so the render thread is never blocked by that work.
 *
 * <p>cloud-sync-uuid-identity FR5.2/FR5.4/Compatibility: {@code worldSlug} is
 * now always a {@code cloudWorldId} (a UUID string), so the restored local
 * folder is named directly with it -- no sanitization/uniquification is
 * needed. Compatibility: if the Cloud key actually being restored from is
 * still old-style (not UUID-shaped -- a world synced before this feature
 * shipped, never restored anywhere else since), this service runs the same
 * Phase A migration codepath ({@link WorldCloudMigrationService}) against
 * the just-extracted folder immediately after a successful restore, so this
 * device's own subsequent syncs of it are UUID-keyed/UUID-folder-named like
 * every other world.
 *
 * <p>Usage example (from {@code WorldRestoreScreen}):
 * <pre>{@code
 * WorldRestoreHook hook = new WorldRestoreService(
 *         archiveStore, preferenceService, worker, savesDirectory, LazuliMod.LOGGER::warn,
 *         LazuliMod.LOGGER::info, migrationService);
 * RestoreHandle handle = hook.beginRestore(cloudWorldId, displayName, listener);
 * }</pre>
 */
public final class WorldRestoreService implements WorldRestoreHook {

    private final WorldArchiveCloudStore archiveStore;
    private final WorldSyncPreferenceService preferenceService;
    private final CloudSyncWorker worker;
    private final Path savesDirectory;
    private final Consumer<String> warningLogger;
    private final Consumer<String> infoLogger;
    private final WorldCloudMigrationService migrationService;
    private final Map<String, RestoreContext> activeRestores = new ConcurrentHashMap<>();

    /**
     * @param archiveStore      the Group 6 Cloud seam (real or no-op)
     * @param preferenceService used to mark a successfully-restored world
     *                          sync-enabled (FR6.10)
     * @param worker            hops extraction work onto a background thread
     * @param savesDirectory    this device's local worlds/saves directory
     * @param warningLogger     receives a human-readable message for any
     *                          internal failure; never invoked with a thrown
     *                          exception
     * @param infoLogger        receives a human-readable message when a world
     *                          download/restore starts and completes
     * @param migrationService  cloud-sync-uuid-identity Compatibility: drives
     *                          the post-restore Phase A migration for a
     *                          world restored from an old-style (pre-UUID)
     *                          Cloud key
     */
    public WorldRestoreService(
            WorldArchiveCloudStore archiveStore,
            WorldSyncPreferenceService preferenceService,
            CloudSyncWorker worker,
            Path savesDirectory,
            Consumer<String> warningLogger,
            Consumer<String> infoLogger,
            WorldCloudMigrationService migrationService) {
        this.archiveStore = Objects.requireNonNull(archiveStore, "archiveStore");
        this.preferenceService = Objects.requireNonNull(preferenceService, "preferenceService");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.savesDirectory = Objects.requireNonNull(savesDirectory, "savesDirectory");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.infoLogger = Objects.requireNonNull(infoLogger, "infoLogger");
        this.migrationService = Objects.requireNonNull(migrationService, "migrationService");
    }

    @Override
    public RestoreHandle beginRestore(String worldSlug, String displayName, RestoreProgressListener listener) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(listener, "listener");

        infoLogger.accept("[DEBUG-RETRY] WorldRestoreService.beginRestore called for worldSlug=\"" + worldSlug
                + "\", activeRestores.containsKey=" + activeRestores.containsKey(worldSlug)
                + ", activeRestores.keySet=" + activeRestores.keySet());

        Path targetWorldFolder = savesDirectory.resolve(worldSlug);
        boolean targetExists = Files.exists(targetWorldFolder);
        infoLogger.accept("[DEBUG-RETRY] beginRestore: targetWorldFolder=" + targetWorldFolder
                + ", exists=" + targetExists);
        if (targetExists) {
            boolean realSave = StaleSaveFolderHealer.isRealSaveFolder(targetWorldFolder);
            infoLogger.accept("[DEBUG-RETRY] beginRestore: targetWorldFolder exists, isRealSaveFolder=" + realSave
                    + " (has readable level.dat=" + realSave + ")");
            if (realSave) {
                infoLogger.accept("[DEBUG-RETRY] beginRestore: EARLY-RETURN onFailed \"already exists\" branch hit for worldSlug=\""
                        + worldSlug + "\" -- this is the branch that would fire on a retry after a first"
                        + " attempt downloaded/moved the folder into place, even if that folder is corrupted/incomplete.");
                listener.onFailed(worldSlug, "A local world folder named \"" + displayName
                        + "\" already exists; restore aborted before any extraction began.");
                return new RestoreHandle(worldSlug);
            }
            // The folder has no level.dat (or is empty) -- it is a stale
            // leftover, not a real save (e.g. from an earlier aborted restore
            // via this exact codepath, which never cleaned up after itself,
            // or a stray folder created some other way). Safe to clear so the
            // world isn't permanently and silently blocked from ever being
            // downloaded again.
            infoLogger.accept("Clearing stale, non-save local folder \"" + worldSlug
                    + "\" (no level.dat) before restoring from Steam Cloud.");
            StaleSaveFolderHealer.deleteRecursively(targetWorldFolder);
        }

        String archiveFileName = WorldSaveSyncService.archiveFileName(worldSlug);
        int totalSize = archiveStore.fileSize(archiveFileName);
        infoLogger.accept("[DEBUG-RETRY] beginRestore: archiveFileName=\"" + archiveFileName
                + "\", archiveStore.fileSize()=" + totalSize);
        if (totalSize <= 0) {
            infoLogger.accept("[DEBUG-RETRY] beginRestore: EARLY-RETURN onFailed \"not found on Steam Cloud\" branch hit for worldSlug=\""
                    + worldSlug + "\" (totalSize=" + totalSize + ")");
            listener.onFailed(worldSlug, "World archive for \"" + displayName + "\" was not found on Steam Cloud.");
            return new RestoreHandle(worldSlug);
        }

        RestoreContext context = new RestoreContext(worldSlug, displayName, listener, totalSize);
        RestoreContext previousContext = activeRestores.put(worldSlug, context);
        infoLogger.accept("[DEBUG-RETRY] beginRestore: registered new RestoreContext for \"" + worldSlug
                + "\" in activeRestores; previousContext for this slug was " + (previousContext == null ? "null (no stale entry)" : "NON-NULL (a stale entry existed and was just overwritten -- possible leak from a prior failed/incomplete attempt)"));

        infoLogger.accept("Downloading world \"" + displayName + "\" (" + totalSize + " bytes) from Steam Cloud.");
        infoLogger.accept("[DEBUG-RETRY] beginRestore: calling archiveStore.beginAsyncRead for \"" + archiveFileName + "\"");
        archiveStore.beginAsyncRead(archiveFileName, new WorldArchiveCloudStore.AsyncReadListener() {
            @Override
            public void onChunk(byte[] chunk) {
                if (context.cancelled) {
                    infoLogger.accept("[DEBUG-RETRY] onChunk: context.cancelled=true for \"" + worldSlug + "\", ignoring chunk of size " + chunk.length);
                    return;
                }
                context.archiveBuffer.writeBytes(chunk);
                long processed = context.archiveBuffer.size();
                listener.onProgress(new RestoreProgress(RestoreProgress.Phase.READING_FROM_CLOUD, processed, totalSize));

                float fraction = DownloadProgressPresenter.combinedFraction(
                        processed, totalSize, 0L, 0L, RestoreProgress.Phase.READING_FROM_CLOUD);
                logMilestoneIfCrossed(context, processed, totalSize, fraction);
            }

            @Override
            public void onComplete() {
                infoLogger.accept("[DEBUG-RETRY] onComplete: archiveStore finished reading \"" + worldSlug
                        + "\", context.cancelled=" + context.cancelled + "; buffered bytes=" + context.archiveBuffer.size());
                if (context.cancelled) {
                    infoLogger.accept("[DEBUG-RETRY] onComplete: cancelled=true, calling finishCancelled for \"" + worldSlug + "\"");
                    finishCancelled(context);
                    return;
                }
                infoLogger.accept("[DEBUG-RETRY] onComplete: submitting extractAndFinish to background worker for \"" + worldSlug + "\"");
                worker.submitBackgroundWork(() -> extractAndFinish(targetWorldFolder, context));
            }

            @Override
            public void onFailed(String reason) {
                infoLogger.accept("[DEBUG-RETRY] onFailed: archiveStore read failed for \"" + worldSlug
                        + "\", reason=\"" + reason + "\"; removing from activeRestores");
                activeRestores.remove(worldSlug);
                listener.onFailed(worldSlug, reason);
            }
        });

        infoLogger.accept("[DEBUG-RETRY] beginRestore: returning RestoreHandle for \"" + worldSlug + "\" (beginAsyncRead call has returned -- note it may be synchronous and already complete by this point)");
        return new RestoreHandle(worldSlug);
    }

    @Override
    public void cancelRestore(RestoreHandle handle) {
        RestoreContext context = activeRestores.get(handle.worldSlug());
        infoLogger.accept("[DEBUG-RETRY] cancelRestore called for worldSlug=\"" + handle.worldSlug()
                + "\", found context=" + (context != null));
        if (context != null) {
            context.cancelled = true;
            infoLogger.accept("[DEBUG-RETRY] cancelRestore: set context.cancelled=true for \"" + handle.worldSlug() + "\"");
        }
    }

    private void extractAndFinish(Path targetWorldFolder, RestoreContext context) {
        infoLogger.accept("[DEBUG-RETRY] extractAndFinish entered for worldSlug=\"" + context.worldSlug
                + "\", targetWorldFolder=" + targetWorldFolder + ", cancelled=" + context.cancelled);
        Path stagingDirectory = savesDirectory.resolve(".tmp-restore-" + context.worldSlug);
        try {
            if (context.cancelled) {
                infoLogger.accept("[DEBUG-RETRY] extractAndFinish: cancelled=true at entry for \"" + context.worldSlug + "\", calling finishCancelled");
                finishCancelled(context);
                return;
            }

            StaleSaveFolderHealer.deleteRecursively(stagingDirectory);
            Files.createDirectories(stagingDirectory);

            byte[] archiveBytes = context.archiveBuffer.toByteArray();
            long totalUncompressed = estimateUncompressedSize(archiveBytes);
            long processed = 0L;

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zip.getNextEntry()) != null) {
                    if (context.cancelled) {
                        throw new IOException("Restore cancelled.");
                    }
                    Path entryPath = stagingDirectory.resolve(entry.getName()).normalize();
                    if (!entryPath.startsWith(stagingDirectory)) {
                        throw new IOException("Archive entry escapes staging directory: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Path parent = entryPath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        try (OutputStream out = Files.newOutputStream(entryPath)) {
                            int read;
                            while ((read = zip.read(buffer)) >= 0) {
                                out.write(buffer, 0, read);
                                processed += read;
                            }
                        }
                    }
                    zip.closeEntry();
                    context.listener.onProgress(
                            new RestoreProgress(RestoreProgress.Phase.EXTRACTING, processed, totalUncompressed));

                    float fraction = DownloadProgressPresenter.combinedFraction(
                            context.readingTotalBytes, context.readingTotalBytes,
                            processed, totalUncompressed, RestoreProgress.Phase.EXTRACTING);
                    logMilestoneIfCrossed(context, processed, totalUncompressed, fraction);
                }
            }

            if (context.cancelled) {
                throw new IOException("Restore cancelled.");
            }

            Files.move(stagingDirectory, targetWorldFolder);
            preferenceService.markEnabledAfterRestore(context.worldSlug);

            // cloud-sync-uuid-identity Compatibility: if the key just
            // restored from was old-style (not UUID-shaped), migrate its
            // Cloud data to a freshly-minted cloudWorldId now -- the
            // just-extracted folder is definitionally not yet loaded, so
            // this rename is always safe immediately (unlike the ordinary
            // Phase B checkpoints, which must wait for a main-menu-guaranteed
            // moment). A folder already named with a UUID (the common case,
            // FR5.4) is a no-op fast path (FR1.2) with zero extra I/O.
            migratePostRestoreIfOldStyleKey(context.worldSlug);

            activeRestores.remove(context.worldSlug);
            infoLogger.accept("[DEBUG-RETRY] extractAndFinish: Files.move succeeded, targetWorldFolder now exists="
                    + Files.exists(targetWorldFolder) + ", isRealSaveFolder="
                    + StaleSaveFolderHealer.isRealSaveFolder(targetWorldFolder)
                    + " -- THIS is the on-disk state a retried beginRestore() will see for \"" + context.worldSlug + "\".");
            infoLogger.accept("Downloaded and restored world \"" + context.displayName + "\" from Steam Cloud.");
            context.listener.onComplete(context.worldSlug);
        } catch (IOException | RuntimeException e) {
            StaleSaveFolderHealer.deleteRecursively(stagingDirectory);
            activeRestores.remove(context.worldSlug);
            infoLogger.accept("[DEBUG-RETRY] extractAndFinish: failed with exception, activeRestores entry removed for \""
                    + context.worldSlug + "\", stagingDirectory deleted, targetWorldFolder exists="
                    + Files.exists(targetWorldFolder));
            warningLogger.accept("Failed to restore world \"" + context.displayName + "\": " + e);
            context.listener.onFailed(context.worldSlug, "Failed to restore world: " + e.getMessage());
        }
    }

    /**
     * cloud-sync-uuid-identity Compatibility: a no-op (zero I/O) if
     * {@code worldSlug} already parses as a UUID (FR5.4's expected common
     * case); otherwise runs {@link WorldCloudMigrationService}'s Phase A
     * against it -- the archive/metadata this restore just pulled from
     * {@code worldSlug}'s old-style key is migrated to a fresh
     * {@code cloudWorldId}, but the local folder is left alone here (it is
     * already named {@code worldSlug}, the old-style key -- see caller's
     * note on why an immediate rename is still safe, unlike the ordinary
     * main-menu-only Phase B checkpoints; this method deliberately performs
     * only Phase A, then runs Phase B once, immediately, since we already
     * know for certain this specific folder is unloaded).
     */
    private void migratePostRestoreIfOldStyleKey(String worldSlug) {
        try {
            UUID.fromString(worldSlug);
            return; // already UUID-shaped -- nothing to migrate (FR5.4).
        } catch (IllegalArgumentException ignored) {
            // Old-style key -- fall through to migrate it below.
        }
        migrationService.resolveCloudWorldId(worldSlug);
        migrationService.runPendingRenames();
    }

    /**
     * FR6.2: logs once, at info level, the first time the combined
     * weighted-fraction (FR3.2, same math {@code DownloadProgressPresenter}
     * uses for the download screen's progress bar) crosses a new 25% boundary
     * (25/50/75/100). Fires from here (not the screen) so the milestone log
     * keeps appearing even after the player has pressed Cancel on the new
     * "Downloading..." screen and the background restore keeps running
     * unattended (FR2.2/FR2.3).
     */
    private void logMilestoneIfCrossed(RestoreContext context, long processed, long total, float fraction) {
        int milestone = milestoneFor(fraction);
        if (milestone > context.lastLoggedMilestone) {
            context.lastLoggedMilestone = milestone;
            infoLogger.accept("Cloud world download \"" + context.displayName + "\": " + milestone + "% ("
                    + DownloadProgressPresenter.formatBytes(processed) + " / "
                    + DownloadProgressPresenter.formatBytes(total) + ").");
        }
    }

    private static int milestoneFor(float fraction) {
        int percentage = Math.round(fraction * 100f);
        if (percentage >= 100) {
            return 100;
        } else if (percentage >= 75) {
            return 75;
        } else if (percentage >= 50) {
            return 50;
        } else if (percentage >= 25) {
            return 25;
        }
        return 0;
    }

    private void finishCancelled(RestoreContext context) {
        infoLogger.accept("[DEBUG-RETRY] finishCancelled: removing activeRestores entry and calling onFailed(\"Restore cancelled.\") for \""
                + context.worldSlug + "\"");
        activeRestores.remove(context.worldSlug);
        context.listener.onFailed(context.worldSlug, "Restore cancelled.");
    }

    private static long estimateUncompressedSize(byte[] archiveBytes) throws IOException {
        long total = 0L;
        boolean anyUnknown = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                long size = entry.getSize();
                if (size < 0) {
                    anyUnknown = true;
                } else {
                    total += size;
                }
                zip.closeEntry();
            }
        }
        return anyUnknown || total == 0 ? archiveBytes.length : total;
    }

    private static final class RestoreContext {
        final String worldSlug;
        final String displayName;
        final RestoreProgressListener listener;
        final long readingTotalBytes;
        final ByteArrayOutputStream archiveBuffer = new ByteArrayOutputStream();
        volatile boolean cancelled;
        volatile int lastLoggedMilestone;

        RestoreContext(String worldSlug, String displayName, RestoreProgressListener listener, long readingTotalBytes) {
            this.worldSlug = worldSlug;
            this.displayName = displayName;
            this.listener = listener;
            this.readingTotalBytes = readingTotalBytes;
        }
    }
}
