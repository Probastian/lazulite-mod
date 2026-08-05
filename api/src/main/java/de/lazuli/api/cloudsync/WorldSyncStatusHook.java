package de.lazuli.api.cloudsync;

/**
 * Stable, Minecraft-free abstraction over a single local world's last-known
 * Steam Cloud world-save sync <em>result</em> this session (companion spec
 * "Diagnostics, Per-World Sync-Status UI, and Archive Compression", FRU.x),
 * consumed by a platform Version Adapter (the per-world status icon drawn
 * immediately left of the existing sync-toggle icon on the vanilla
 * Singleplayer world-select screen) and implemented by
 * {@code features/steam-cloud-sync}'s own {@code WorldSyncStatusTracker}.
 *
 * <p>Deliberately a separate interface from {@link WorldSyncToggleHook}
 * (which only ever expresses the on/off sync <em>preference</em>) -- this
 * hook expresses the outcome of the most recent sync attempt, if any, this
 * session.
 *
 * <p>Usage example (from a platform Version Adapter holding a
 * constructor-injected {@code WorldSyncStatusHook}):
 * <pre>{@code
 * WorldSyncStatusHook hook = ...; // supplied by the platform composition root
 * SyncStatus status = hook.statusFor(worldFolderName);
 * if (status == SyncStatus.SYNC_ERROR) {
 *     tooltip.setText(hook.lastErrorFor(worldFolderName));
 * }
 * }</pre>
 */
public interface WorldSyncStatusHook {

    /** The four sync-status states a local world can be in this session (FRU.1). */
    enum SyncStatus {
        /**
         * Either the sync preference is off, or it is on but no successful
         * sync has completed yet this session.
         */
        NOT_SYNCED,
        /** The most recent sync attempt this session succeeded. */
        SYNCED,
        /** The most recent sync attempt this session failed. */
        SYNC_ERROR,
        /**
         * The most recent sync attempt this session was skipped because the
         * world exceeds the configured size threshold and selective fallback
         * was not allowed/available.
         */
        SKIPPED_TOO_LARGE
    }

    /**
     * @param worldSlug the world's on-disk save-folder name
     * @return this session's last-known sync status for this world; defaults
     *         to {@link SyncStatus#NOT_SYNCED} for a world with no recorded
     *         sync attempt yet this session
     */
    SyncStatus statusFor(String worldSlug);

    /**
     * @param worldSlug the world's on-disk save-folder name
     * @return a human-readable message describing the most recent sync
     *         failure, or {@code null} unless {@link #statusFor(String)}
     *         currently returns {@link SyncStatus#SYNC_ERROR} for this world
     */
    String lastErrorFor(String worldSlug);

    /**
     * @param worldSlug the world's on-disk save-folder name
     * @return {@code true} if an upload for this world is currently either
     *         queued/pending or actively in flight (FR-P1 of the
     *         "Cloud Sync Status UI" companion spec); defaults to
     *         {@code false} via this default method so any out-of-tree
     *         implementer of this interface predating FR-P1 keeps compiling
     */
    default boolean isUploadInProgress(String worldSlug) {
        return false;
    }

    /**
     * @param worldSlug the world's on-disk save-folder name
     * @return {@code true} if a "Keep Cloud" conflict-resolution restore is
     *         currently running for this world (cloud-sync-status-ui-simplify
     *         spec's FR-3.2 -- the one downstream/download-shaped transfer
     *         that can touch an already-real world row); defaults to
     *         {@code false} via this default method so any out-of-tree
     *         implementer predating FR-3.2 keeps compiling, mirroring
     *         {@link #isUploadInProgress(String)}'s own precedent
     */
    default boolean isDownloadInProgress(String worldSlug) {
        return false;
    }

    /**
     * Marks {@code worldSlug} as having a "Keep Cloud" restore actively
     * running (FR-3.2). Placed on this interface (rather than only on the
     * concrete {@code WorldSyncStatusTracker}) because {@code WorldsPanel}'s
     * composition-root wiring ({@code WorldSyncStatusHookHolder}) only ever
     * vends this interface, not the concrete tracker, so {@code
     * WorldConflictScreen} -- constructed from {@code WorldsPanel} -- cannot
     * downcast to call a tracker-only method. Defaults to a no-op so any
     * out-of-tree implementer predating FR-3.2 keeps compiling.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    default void markDownloadPending(String worldSlug) {
        // no-op default
    }

    /**
     * Clears the download-in-progress flag set by
     * {@link #markDownloadPending(String)}. Must be called in both the
     * success and failure branches of the "Keep Cloud" restore (FR-3.2), so
     * a failed restore doesn't leave the flag stuck true.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    default void markDownloadFinished(String worldSlug) {
        // no-op default
    }

    /**
     * Gap 2 (sync-conflict-coverage-gaps spec): {@code true} while an async,
     * toggle-on-triggered {@code checkConflictFor} is in flight for this
     * world -- set right after {@code WorldSyncToggleHook.toggleSync}
     * transitions a world from disabled to enabled, cleared once the check's
     * result (conflict or not) has been handled. {@code WorldsPanel}'s
     * {@code blocked} gate must treat this exactly like
     * {@link #isUploadInProgress(String)}. Defaults to {@code false} via
     * this default method so any out-of-tree implementer predating this gap
     * fix keeps compiling.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    default boolean isConflictCheckPending(String worldSlug) {
        return false;
    }
}
