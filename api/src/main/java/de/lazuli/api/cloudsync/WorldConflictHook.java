package de.lazuli.api.cloudsync;

/**
 * Stable, Minecraft-free abstraction over the FR-V/F20e true two-sided
 * conflict classification for a single local world (companion spec
 * "Cloud Sync Status UI" addendum), consumed by a platform Version Adapter
 * (the Worlds tab's conflict indicator/{@code WorldConflictScreen} trigger)
 * and implemented by {@code features/steam-cloud-sync}'s own
 * {@code WorldSaveSyncService}.
 *
 * <p>Deliberately a separate, minimal interface from
 * {@link WorldFreshnessHook} -- a conflict is an orthogonal, rarer condition
 * layered on top of, not replacing, the existing one-sided freshness
 * classification (a {@code STALE} world may or may not also be
 * {@code CONFLICT}).
 */
public interface WorldConflictHook {

    /** The FR-V.2/F20e two-sided-conflict classification for a local world. */
    enum ConflictStatus {
        /** No known two-sided divergence for this world. */
        NONE,
        /**
         * The local copy diverged since this device's own last known sync
         * <strong>and</strong> the Cloud copy diverged from a different
         * device since that same point.
         */
        CONFLICT
    }

    /**
     * @param worldSlug               the world's on-disk save-folder name
     * @param worldFolderAbsolutePath the world's on-disk save folder's
     *                                absolute path, as a plain string
     * @return this world's current conflict classification
     */
    ConflictStatus checkConflictFor(String worldSlug, String worldFolderAbsolutePath);
}
