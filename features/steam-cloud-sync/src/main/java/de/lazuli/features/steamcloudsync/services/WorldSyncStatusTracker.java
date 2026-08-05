package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.WorldSyncStatusHook;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-only, in-memory tracker of each local world's last sync-attempt
 * outcome (FRU.3 of the "Diagnostics, Per-World Sync-Status UI, and Archive
 * Compression" companion spec), keyed by {@code worldSlug}. Constructed once
 * by the composition root and injected into {@link WorldSaveSyncService}
 * alongside its existing {@code warningLogger}/{@code playerNotifier}
 * collaborators; implements {@link WorldSyncStatusHook} so a platform Version
 * Adapter (the per-world status icon) can hold this class directly, mirroring
 * {@link WorldSyncPreferenceService}'s implementation of the sibling
 * {@code WorldSyncToggleHook}.
 *
 * <p>Backed by {@link ConcurrentHashMap} rather than
 * {@code WorldSyncPreferenceService}'s {@code synchronized}/{@code LinkedHashMap}
 * shape: {@link WorldSaveSyncService} writes into this tracker from both
 * {@code CloudSyncWorker}'s background thread and the client tick thread,
 * while the render-thread mixin reads it every frame -- a concurrent map
 * needs no external synchronization for these simple put/get accesses.
 *
 * <p>Not persisted across a client restart (Future Extensions of the
 * companion spec) -- every world starts each session as {@code NOT_SYNCED}
 * until a sync checkpoint actually runs for it.
 */
public final class WorldSyncStatusTracker implements WorldSyncStatusHook {

    private final ConcurrentHashMap<String, SyncStatus> statuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastErrors = new ConcurrentHashMap<>();
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingConflicts = ConcurrentHashMap.newKeySet();
    private final Set<String> downloadInProgress = ConcurrentHashMap.newKeySet();
    private final Set<String> conflictCheckPending = ConcurrentHashMap.newKeySet();

    /**
     * Marks {@code worldSlug} as having an upload handed off for background
     * processing -- called at hand-off time (e.g. the top of
     * {@link WorldSaveSyncService#onWorldUnload}, immediately before
     * submitting to {@link CloudSyncWorker}), not from within the
     * asynchronous work itself, so this covers both the queued/pending
     * window and the actively-uploading window (FR-P1).
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void markUploadPending(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        inProgress.add(worldSlug);
    }

    /**
     * Clears the in-progress flag for {@code worldSlug}. Must be called in
     * every terminal branch of a sync attempt (success, error, skipped, or
     * an exception during archive building), typically via
     * {@code try/finally}.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void markUploadFinished(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        inProgress.remove(worldSlug);
    }

    @Override
    public boolean isUploadInProgress(String worldSlug) {
        return inProgress.contains(worldSlug);
    }

    /**
     * cloud-sync-status-ui-simplify FR-3.2: marks {@code worldSlug} as having
     * a "Keep Cloud" conflict-resolution restore actively running -- set by
     * {@code WorldConflictScreen.onKeepCloud()} immediately before calling
     * {@code WorldRestoreHook.beginRestore}, mirroring {@link
     * #markUploadPending(String)}'s shape.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    @Override
    public void markDownloadPending(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        downloadInProgress.add(worldSlug);
    }

    /**
     * Clears the download-in-progress flag for {@code worldSlug}. Called in
     * both the {@code onComplete} and {@code onFailed} branches of the "Keep
     * Cloud" restore's {@code RestoreProgressListener} (FR-3.2), mirroring
     * {@link #markUploadFinished(String)}'s shape.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    @Override
    public void markDownloadFinished(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        downloadInProgress.remove(worldSlug);
    }

    @Override
    public boolean isDownloadInProgress(String worldSlug) {
        return downloadInProgress.contains(worldSlug);
    }

    /**
     * FR-V.6: marks {@code worldSlug} as having an unresolved, pending
     * two-sided conflict -- set once a checkpoint's {@code checkConflictFor}
     * first returns {@code CONFLICT} and the UI has surfaced it, cleared by
     * {@link #clearPendingConflict(String)} once the player chooses "Keep
     * Local"/"Keep Cloud" (or closes the screen without choosing).
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void markConflictPending(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        pendingConflicts.add(worldSlug);
    }

    /**
     * Clears the pending-conflict flag for {@code worldSlug}.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void clearPendingConflict(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        pendingConflicts.remove(worldSlug);
    }

    /**
     * FR-V.6: while {@code true}, {@link WorldSaveSyncService}'s automatic
     * upload trigger paths ({@code onWorldSaved},
     * {@code checkAndUploadStaleWorldsAtStartup}) must no-op for this world,
     * so the automatic trigger points don't race the player's pending
     * decision and silently resolve the conflict before they've seen the
     * screen.
     *
     * @param worldSlug the world's on-disk save-folder name
     * @return {@code true} if a conflict is pending and unresolved
     */
    public boolean hasPendingConflict(String worldSlug) {
        return pendingConflicts.contains(worldSlug);
    }

    /**
     * Gap 2 (sync-conflict-coverage-gaps): marks {@code worldSlug} as having
     * an async, toggle-on-triggered {@code checkConflictFor} in flight --
     * set synchronously right after {@code WorldSyncPreferenceService
     * .toggleSync} flips a world's preference from disabled to enabled,
     * before the check is submitted to the background worker. While set,
     * {@code WorldsPanel}'s {@code blocked} gate must treat this exactly
     * like {@link #isUploadInProgress(String)} (Play/Edit disabled).
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void markConflictCheckPending(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        conflictCheckPending.add(worldSlug);
    }

    /**
     * Clears the transient conflict-check-pending flag for {@code worldSlug}.
     * Must be called in both terminal branches of the async check (result is
     * {@code CONFLICT}, or the check finds no conflict and the normal sync
     * flow proceeds), typically via {@code try/finally}. Idempotent -- safe
     * to call on a world with no pending check.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void clearConflictCheckPending(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        conflictCheckPending.remove(worldSlug);
    }

    /**
     * @param worldSlug the world's on-disk save-folder name
     * @return {@code true} while an async toggle-on conflict check is in
     *         flight for {@code worldSlug}
     */
    @Override
    public boolean isConflictCheckPending(String worldSlug) {
        return conflictCheckPending.contains(worldSlug);
    }

    /**
     * Records a successful sync for {@code worldSlug}, clearing any
     * previously recorded error text.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void markSynced(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        statuses.put(worldSlug, SyncStatus.SYNCED);
        lastErrors.remove(worldSlug);
    }

    /**
     * Records a failed sync attempt for {@code worldSlug}.
     *
     * @param worldSlug the world's on-disk save-folder name
     * @param message   a human-readable failure message; may be {@code null}
     */
    public void markError(String worldSlug, String message) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        statuses.put(worldSlug, SyncStatus.SYNC_ERROR);
        lastErrors.put(worldSlug, message == null ? "Unknown Steam Cloud sync failure." : message);
    }

    /**
     * Records a size-threshold-exceeded skip for {@code worldSlug}.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void markSkippedTooLarge(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        statuses.put(worldSlug, SyncStatus.SKIPPED_TOO_LARGE);
        lastErrors.remove(worldSlug);
    }

    /**
     * Request 3 (cloud-sync-threshold-and-full-sync-only): clears
     * {@code worldSlug} back to the default {@link SyncStatus#NOT_SYNCED}
     * (removes it from both the status and last-error maps) -- used after a
     * successful un-sync Cloud deletion so a world that's no longer
     * Cloud-backed doesn't keep showing a stale {@code SYNCED}/error status.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void clearStatus(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        statuses.remove(worldSlug);
        lastErrors.remove(worldSlug);
    }

    @Override
    public SyncStatus statusFor(String worldSlug) {
        return statuses.getOrDefault(worldSlug, SyncStatus.NOT_SYNCED);
    }

    @Override
    public String lastErrorFor(String worldSlug) {
        return statusFor(worldSlug) == SyncStatus.SYNC_ERROR ? lastErrors.get(worldSlug) : null;
    }
}
