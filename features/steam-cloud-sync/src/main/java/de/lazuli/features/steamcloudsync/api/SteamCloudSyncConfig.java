package de.lazuli.features.steamcloudsync.api;

/**
 * This feature's own local settings (Configuration section of the
 * specification): one master switch, one independent toggle per Cloud-synced
 * group (Groups 1-5; Group 6 has no shared toggle here by design -- its
 * opt-in state lives entirely in the per-world {@link WorldSyncPreference}
 * list, FR6.1), and the Group 6 size-limiting knobs.
 *
 * <p>Backed by {@code config/steam-cloud-sync.json}
 * (see {@code de.lazuli.features.steamcloudsync.config.SteamCloudSyncConfigIO}):
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "enabled": true,
 *   "syncSettings": true,
 *   "syncAccessibility": true,
 *   "syncBookmarkedServers": true,
 *   "syncContinuePointer": true,
 *   "syncNotes": true
 * }
 * }</pre>
 *
 * <p>Usage example:
 * <pre>{@code
 * SteamCloudSyncConfig config = SteamCloudSyncConfig.DEFAULT;
 * if (config.enabled() && config.syncBookmarkedServers()) {
 *     // sync the bookmarked-servers file at this checkpoint
 * }
 * }</pre>
 *
 * <p>Note: the Group 6 world-archive size threshold is intentionally
 * <strong>not</strong> a field of this config -- it is a hardcoded,
 * non-configurable constant
 * ({@link de.lazuli.features.steamcloudsync.services.WorldSaveSyncService#MAX_WORLD_ARCHIVE_SIZE_MB})
 * that is never read from or written to any on-disk file (see that
 * constant's own Javadoc). Likewise, the critical-files-only selective-sync
 * fallback has been removed entirely -- sync is strictly all-or-nothing.
 *
 * @param schemaVersion          this config schema's version, for future
 *                               evolution
 * @param enabled                master switch; even when {@code true}, has no
 *                               effect unless Steam is available. Also gates
 *                               whether the Group 6 world-select-screen
 *                               sync-toggle icon appears at all
 * @param syncSettings           Group 1 (mod feature settings) toggle
 * @param syncAccessibility      Group 2 (UI/accessibility preferences) toggle
 * @param syncBookmarkedServers  Group 3 (bookmarked servers) toggle
 * @param syncContinuePointer    Group 4 (continue-where-you-left-off) toggle
 * @param syncNotes              Group 5 (notes/waypoints) toggle
 */
public record SteamCloudSyncConfig(
        int schemaVersion,
        boolean enabled,
        boolean syncSettings,
        boolean syncAccessibility,
        boolean syncBookmarkedServers,
        boolean syncContinuePointer,
        boolean syncNotes) {

    /** The current schema version this feature writes/expects. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * The default configuration used when no config file exists yet, or when
     * an existing file fails to parse.
     */
    public static final SteamCloudSyncConfig DEFAULT =
            new SteamCloudSyncConfig(CURRENT_SCHEMA_VERSION, true, true, true, true, true, true);
}
