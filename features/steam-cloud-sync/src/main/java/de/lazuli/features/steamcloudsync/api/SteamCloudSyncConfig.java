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
 *   "syncNotes": true,
 *   "maxWorldArchiveSizeMb": 1024,
 *   "allowSelectiveFallback": true
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
 * @param maxWorldArchiveSizeMb  the size threshold (megabytes) above which a
 *                               world's save folder falls back to selective
 *                               sync (or is skipped, if
 *                               {@link #allowSelectiveFallback()} is
 *                               {@code false}) instead of a whole-archive zip
 * @param allowSelectiveFallback whether to fall back to a small
 *                               critical-files-only archive (FR6.4) for a
 *                               world over {@link #maxWorldArchiveSizeMb()},
 *                               rather than skipping sync for it entirely
 */
public record SteamCloudSyncConfig(
        int schemaVersion,
        boolean enabled,
        boolean syncSettings,
        boolean syncAccessibility,
        boolean syncBookmarkedServers,
        boolean syncContinuePointer,
        boolean syncNotes,
        int maxWorldArchiveSizeMb,
        boolean allowSelectiveFallback) {

    /** The current schema version this feature writes/expects. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * The default configuration used when no config file exists yet, or when
     * an existing file fails to parse.
     */
    public static final SteamCloudSyncConfig DEFAULT =
            new SteamCloudSyncConfig(CURRENT_SCHEMA_VERSION, true, true, true, true, true, true, 1024, true);
}
