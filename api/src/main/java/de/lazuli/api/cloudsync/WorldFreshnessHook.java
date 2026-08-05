package de.lazuli.api.cloudsync;

/**
 * Stable, Minecraft-free abstraction over the FR-P3 up-to-date/stale/unknown
 * freshness classification for a single local world (companion spec
 * "Cloud Sync Status UI"), consumed by a platform Version Adapter (the
 * per-world freshness indicator drawn on the Worlds tab, FR-Q.2) and
 * implemented by {@code features/steam-cloud-sync}'s own
 * {@code WorldSaveSyncService}.
 *
 * <p>Deliberately a separate interface from {@link WorldSyncStatusHook}
 * (which expresses this session's last sync-attempt outcome) -- this hook
 * expresses whether the local copy is believed to still match the last
 * Steam Cloud sync from this device, computed from timestamps/fingerprints
 * only (never a content hash, never a new Steam API call).
 *
 * <p>Usage example (from a platform Version Adapter holding a
 * constructor-injected {@code WorldFreshnessHook}):
 * <pre>{@code
 * WorldFreshnessHook hook = ...; // supplied by the platform composition root
 * UpToDateStatus status = hook.upToDateStatusFor(worldFolderName, worldFolderAbsolutePath);
 * }</pre>
 */
public interface WorldFreshnessHook {

    /** The FR-P3 up-to-date/stale/unknown classification for a local world. */
    enum UpToDateStatus {
        /**
         * This device's own recorded fingerprint for this world is at least
         * as recent as the local folder's own last-modified time.
         */
        UP_TO_DATE,
        /**
         * Either the local folder changed since this device's own last
         * recorded upload, or a different device's fingerprint is the most
         * recently recorded one for this world.
         */
        STALE,
        /** No fingerprint is recorded for this world at all yet. */
        UNKNOWN
    }

    /**
     * @param worldSlug             the world's on-disk save-folder name
     * @param worldFolderAbsolutePath the world's on-disk save folder's
     *                              absolute path, as a plain string (kept a
     *                              {@code String}, not a
     *                              {@code java.nio.file.Path}, so this
     *                              interface's call shape matches every
     *                              other Hook in this package)
     * @return this world's current freshness classification
     */
    UpToDateStatus upToDateStatusFor(String worldSlug, String worldFolderAbsolutePath);

    /**
     * FR-U.1's richer, tooltip-oriented variant of
     * {@link #upToDateStatusFor(String, String)}: the same classification,
     * plus (where applicable) the concrete timestamps/device label backing
     * it, so a hover tooltip can show real per-instance detail instead of a
     * fixed generic string per state. Additive -- does not replace or change
     * {@link #upToDateStatusFor(String, String)}'s existing contract/callers.
     *
     * <p>Default implementation degrades to the bare classification with no
     * detail fields populated, so an out-of-tree implementer of this
     * interface (if any) is not forced to implement this method.
     *
     * @param worldSlug               the world's on-disk save-folder name
     * @param worldFolderAbsolutePath the world's on-disk save folder's
     *                                absolute path, as a plain string
     * @return this world's current freshness classification, plus detail
     */
    default FreshnessDetail upToDateStatusDetailFor(String worldSlug, String worldFolderAbsolutePath) {
        return new FreshnessDetail(upToDateStatusFor(worldSlug, worldFolderAbsolutePath), null, -1L, -1L, -1L);
    }

    /**
     * FR-U.1's tooltip-oriented detail record, additive to {@link UpToDateStatus}.
     * Fields not applicable to a given {@link #status} are left at their
     * sentinel values ({@code null}/{@code -1L}) rather than throwing.
     *
     * @param status                     the plain classification
     * @param otherDeviceLabel           for a {@code STALE} classification
     *                                    caused by a different device's more
     *                                    recent fingerprint, that device's
     *                                    label; {@code null} otherwise
     * @param otherDeviceSyncedAtTimestamp for the same case, that
     *                                    fingerprint's {@code syncedAtTimestamp};
     *                                    {@code -1L} otherwise
     * @param localLastModifiedMillis    the local world folder's own
     *                                    last-modified time, if computed for
     *                                    this classification; {@code -1L} if
     *                                    not applicable/not computed
     * @param ownSyncedAtTimestamp       this device's own last recorded
     *                                    upload timestamp for this world, if
     *                                    a fingerprint exists for it;
     *                                    {@code -1L} if none
     */
    record FreshnessDetail(
            UpToDateStatus status,
            String otherDeviceLabel,
            long otherDeviceSyncedAtTimestamp,
            long localLastModifiedMillis,
            long ownSyncedAtTimestamp) {
    }
}
