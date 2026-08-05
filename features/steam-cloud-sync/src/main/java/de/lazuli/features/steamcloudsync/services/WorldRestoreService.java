package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.DownloadProgressPresenter;
import de.lazuli.api.cloudsync.RestoreHandle;
import de.lazuli.api.cloudsync.RestoreProgress;
import de.lazuli.api.cloudsync.RestoreProgressListener;
import de.lazuli.api.cloudsync.WorldRestoreHook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
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
 * <p>{@link #beginRestore(String, RestoreProgressListener)} performs the
 * FR6.13 same-slug collision check synchronously (cheap) and kicks off the
 * Cloud read on the calling thread (Minecraft's client thread, per this
 * project's single-thread Steamworks convention -- cheap, same as any other
 * small Cloud call, Architecture -- Threading); decompression/extraction is
 * handed off to {@link CloudSyncWorker}'s background thread once every chunk
 * has been read, so the render thread is never blocked by that work.
 *
 * <p>Usage example (from {@code WorldRestoreScreen}):
 * <pre>{@code
 * WorldRestoreHook hook = new WorldRestoreService(
 *         archiveStore, preferenceService, worker, savesDirectory, LazuliMod.LOGGER::warn);
 * RestoreHandle handle = hook.beginRestore(worldSlug, listener);
 * }</pre>
 */
public final class WorldRestoreService implements WorldRestoreHook {

    private final WorldArchiveCloudStore archiveStore;
    private final WorldSyncPreferenceService preferenceService;
    private final CloudSyncWorker worker;
    private final Path savesDirectory;
    private final Consumer<String> warningLogger;
    private final Consumer<String> infoLogger;
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
     */
    public WorldRestoreService(
            WorldArchiveCloudStore archiveStore,
            WorldSyncPreferenceService preferenceService,
            CloudSyncWorker worker,
            Path savesDirectory,
            Consumer<String> warningLogger) {
        this(archiveStore, preferenceService, worker, savesDirectory, warningLogger, message -> { });
    }

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
     */
    public WorldRestoreService(
            WorldArchiveCloudStore archiveStore,
            WorldSyncPreferenceService preferenceService,
            CloudSyncWorker worker,
            Path savesDirectory,
            Consumer<String> warningLogger,
            Consumer<String> infoLogger) {
        this.archiveStore = Objects.requireNonNull(archiveStore, "archiveStore");
        this.preferenceService = Objects.requireNonNull(preferenceService, "preferenceService");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.savesDirectory = Objects.requireNonNull(savesDirectory, "savesDirectory");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.infoLogger = Objects.requireNonNull(infoLogger, "infoLogger");
    }

    @Override
    public RestoreHandle beginRestore(String worldSlug, RestoreProgressListener listener) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        Objects.requireNonNull(listener, "listener");

        Path targetWorldFolder = savesDirectory.resolve(worldSlug);
        if (Files.exists(targetWorldFolder)) {
            if (isRealSaveFolder(targetWorldFolder)) {
                listener.onFailed(worldSlug, "A local world folder named \"" + worldSlug
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
            deleteRecursively(targetWorldFolder);
        }

        String archiveFileName = WorldSaveSyncService.archiveFileName(worldSlug);
        int totalSize = archiveStore.fileSize(archiveFileName);
        if (totalSize <= 0) {
            listener.onFailed(worldSlug, "World archive \"" + archiveFileName + "\" was not found on Steam Cloud.");
            return new RestoreHandle(worldSlug);
        }

        RestoreContext context = new RestoreContext(worldSlug, listener, totalSize);
        activeRestores.put(worldSlug, context);

        infoLogger.accept("Downloading world \"" + worldSlug + "\" (" + totalSize + " bytes) from Steam Cloud.");
        archiveStore.beginAsyncRead(archiveFileName, new WorldArchiveCloudStore.AsyncReadListener() {
            @Override
            public void onChunk(byte[] chunk) {
                if (context.cancelled) {
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
                if (context.cancelled) {
                    finishCancelled(context);
                    return;
                }
                worker.submitBackgroundWork(() -> extractAndFinish(targetWorldFolder, context));
            }

            @Override
            public void onFailed(String reason) {
                activeRestores.remove(worldSlug);
                listener.onFailed(worldSlug, reason);
            }
        });

        return new RestoreHandle(worldSlug);
    }

    @Override
    public void cancelRestore(RestoreHandle handle) {
        RestoreContext context = activeRestores.get(handle.worldSlug());
        if (context != null) {
            context.cancelled = true;
        }
    }

    private void extractAndFinish(Path targetWorldFolder, RestoreContext context) {
        Path stagingDirectory = savesDirectory.resolve(".tmp-restore-" + context.worldSlug);
        try {
            if (context.cancelled) {
                finishCancelled(context);
                return;
            }

            deleteRecursively(stagingDirectory);
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
            activeRestores.remove(context.worldSlug);
            infoLogger.accept("Downloaded and restored world \"" + context.worldSlug + "\" from Steam Cloud.");
            context.listener.onComplete(context.worldSlug);
        } catch (IOException | RuntimeException e) {
            deleteRecursively(stagingDirectory);
            activeRestores.remove(context.worldSlug);
            warningLogger.accept("Failed to restore world \"" + context.worldSlug + "\": " + e);
            context.listener.onFailed(context.worldSlug, "Failed to restore world: " + e.getMessage());
        }
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
            infoLogger.accept("Cloud world download \"" + context.worldSlug + "\": " + milestone + "% ("
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

    /**
     * FR6.13 hardening: distinguishes a real, previously-restored/created
     * save (must never be destroyed) from a stale/invalid leftover folder at
     * the same slug (safe to clear so the world isn't permanently blocked).
     * A folder is only ever treated as "real" when it contains a readable
     * {@code level.dat} -- the same file vanilla's own {@code LevelStorage}
     * requires to load a save, so this matches the exact criterion that
     * causes such a folder to be silently skipped by {@code WorldsPanel}'s
     * local-world scan in the first place.
     */
    private static boolean isRealSaveFolder(Path candidate) {
        if (!Files.isDirectory(candidate)) {
            // A plain file (not a directory) at this path is never a real
            // save folder either; treat it the same as an empty leftover.
            return false;
        }
        return Files.isRegularFile(candidate.resolve("level.dat"));
    }

    private static void deleteRecursively(Path path) {
        if (Files.notExists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup; a leftover staging file is harmless (never visible as a real world).
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private static final class RestoreContext {
        final String worldSlug;
        final RestoreProgressListener listener;
        final long readingTotalBytes;
        final ByteArrayOutputStream archiveBuffer = new ByteArrayOutputStream();
        volatile boolean cancelled;
        volatile int lastLoggedMilestone;

        RestoreContext(String worldSlug, RestoreProgressListener listener, long readingTotalBytes) {
            this.worldSlug = worldSlug;
            this.listener = listener;
            this.readingTotalBytes = readingTotalBytes;
        }
    }
}
