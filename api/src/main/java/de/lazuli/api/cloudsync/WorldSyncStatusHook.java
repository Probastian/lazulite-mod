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
}
