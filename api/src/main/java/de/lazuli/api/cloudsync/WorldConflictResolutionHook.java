package de.lazuli.api.cloudsync;

/**
 * Drives {@code WorldConflictScreen}'s two actions ("Keep Local"/"Keep
 * Cloud") plus the screen-content read (FR-V.3), implemented by
 * {@code features/steam-cloud-sync}'s own {@code WorldSaveSyncService}.
 *
 * <p>Per FR-V.5, "Keep Cloud" itself is driven by the caller re-using the
 * already-stable {@link WorldRestoreHook#beginRestore(String, RestoreProgressListener)}
 * machinery directly -- this hook is only asked to update the local ancestor
 * record afterward ({@link #recordKeepCloudResolution}), never to perform the
 * restore itself, keeping {@code WorldConflictScreen} and
 * {@code WorldRestoreScreen} fully decoupled implementation-wise.
 */
public interface WorldConflictResolutionHook {

    /**
     * FR-V.3/cloud-sync-conflict-ux FR-2/FR-3's screen-content read: every
     * F11 local-vs-cloud field this feature surfaces. The four
     * platform-sourced parameters ({@code gameModeDisplayName}/
     * {@code lastPlayedMillis}/{@code hardcore}/{@code levelDatBatch}) are
     * supplied by the caller (a platform {@code WorldsPanel}) because this
     * Minecraft-client-type-free module cannot itself read a
     * {@code LevelSummary} or open a {@code LevelStorageAccess}.
     *
     * @param worldSlug               the world's on-disk save-folder name
     * @param worldFolderAbsolutePath the world's on-disk save folder's
     *                                absolute path, as a plain string
     * @param displayName             a player-facing name for the world
     * @param gameModeDisplayName     the local world's game mode, already
     *                                resolved to a display string by the
     *                                caller (e.g.
     *                                {@code LevelSummary.getGameMode()}'s own
     *                                display name)
     * @param lastPlayedMillis        the local world's last-played time,
     *                                epoch millis, from {@code LevelSummary}
     * @param hardcore                the local world's hardcore flag, from
     *                                {@code LevelSummary}
     * @param levelDatBatch           the local world's batched
     *                                {@code level.dat} NBT read (seed,
     *                                difficulty, cheats-enabled, day count,
     *                                Minecraft version), performed once by
     *                                the caller before this call
     * @return the detail to render, or {@code null} if no conflict/no
     *         fingerprint data is available for this world any more (e.g.
     *         the conflict resolved itself between detection and screen open)
     */
    ConflictDetail detailFor(String worldSlug, String worldFolderAbsolutePath, String displayName,
            String gameModeDisplayName, long lastPlayedMillis, boolean hardcore, LevelDatBatch levelDatBatch);

    /**
     * FR-V.4's "Keep Local" action: re-uploads the local copy so it becomes
     * the new latest global fingerprint entry (never silently pulls Cloud
     * content over local), and updates this device's ancestor record to the
     * new post-upload state as a side effect.
     *
     * @param worldSlug   the world's on-disk save-folder name
     * @param worldFolderAbsolutePath the world's on-disk save folder's
     *                                absolute path, as a plain string
     * @param displayName a player-facing name for the world
     */
    void resolveKeepLocal(String worldSlug, String worldFolderAbsolutePath, String displayName);

    /**
     * FR-V.4's "Keep Cloud" action's ancestor-update half: called after the
     * caller's own {@link WorldRestoreHook#beginRestore} completes
     * successfully, recording the now-locally-adopted Cloud fingerprint as
     * this device's new ancestor so the conflict does not immediately
     * re-trigger next check.
     *
     * @param worldSlug               the world's on-disk save-folder name
     * @param cloudDeviceLabel        the device label of the fingerprint
     *                                that was just adopted locally
     * @param cloudSyncedAtTimestamp  that fingerprint's {@code syncedAtTimestamp}
     */
    void recordKeepCloudResolution(String worldSlug, String cloudDeviceLabel, long cloudSyncedAtTimestamp);

    /**
     * FR-V.6: clears the pending-conflict blocking flag for {@code worldSlug}
     * (e.g. if the player closes the screen without choosing an action --
     * the conflict will simply be re-detected and re-surfaced at the next
     * freshness-recompute checkpoint rather than staying silently blocked
     * forever).
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    void clearPendingConflict(String worldSlug);

    /**
     * cloud-sync-conflict-ux F11's final field list, restructured (from the
     * previous flat 6-field shape) into one {@link LocalDetail}/
     * {@link CloudDetail} pair per side, mirroring {@code WorldConflictScreen}'s
     * FR-3 two-box layout.
     *
     * @param worldSlug the world's on-disk save-folder name
     * @param local     the local side's field set (F11 rows 1-14)
     * @param cloud     the cloud side's field set (F11 rows 1-4 only -- rows
     *                  5-14 have no cloud counterpart)
     */
    record ConflictDetail(String worldSlug, LocalDetail local, CloudDetail cloud) {

        /**
         * @param displayName                 the local world's player-facing
         *                                     name
         * @param lastModifiedMillis          the local world folder's own
         *                                     last-modified time, epoch
         *                                     millis
         * @param folderSizeBytes             the local world folder's total
         *                                     on-disk size, in bytes
         * @param deviceLabel                 this device's own resolved
         *                                     label
         * @param ancestorSyncedAtTimestamp   this device's last known
         *                                     common-ancestor sync
         *                                     timestamp for this world, or
         *                                     {@code null} if no ancestor
         *                                     entry exists yet
         * @param gameMode                    the local world's game mode,
         *                                     pre-formatted for display
         * @param lastPlayedMillis            the local world's last-played
         *                                     time, epoch millis
         * @param hardcore                    the local world's hardcore flag
         * @param cheatsEnabled                the local world's
         *                                     cheats/{@code allowCommands}
         *                                     flag; meaningless if
         *                                     {@code levelDatReadable} is
         *                                     {@code false}
         * @param difficulty                   the local world's difficulty,
         *                                     pre-formatted for display, or
         *                                     {@code null} if the
         *                                     {@code level.dat} read failed
         * @param seed                         the local world's seed, or
         *                                     {@code null} if the
         *                                     {@code level.dat} read failed
         * @param minecraftVersion              the Minecraft version the
         *                                     world was last played with, or
         *                                     {@code null} if unavailable
         * @param dayCount                     the local world's elapsed
         *                                     in-game day count, or
         *                                     {@code -1} if the
         *                                     {@code level.dat} read failed
         * @param regionFileCount               the number of {@code .mca}
         *                                     region files under the local
         *                                     world folder's {@code region}
         *                                     directory, or {@code -1} if
         *                                     that directory could not be
         *                                     listed
         * @param levelDatReadable              {@code false} if the batched
         *                                     {@code level.dat} NBT read
         *                                     failed entirely -- every
         *                                     {@code level.dat}-sourced
         *                                     field above is then meaningless
         *                                     and should render as "Unknown"
         * @param contentSignature              cloud-world-metadata-file spec
         *                                     Requirement 6: a SHA-256
         *                                     whole-folder content hash for
         *                                     the local world folder,
         *                                     computed the same way the Cloud
         *                                     metadata file's own
         *                                     {@code contentSignature} is, or
         *                                     {@code null} if it could not be
         *                                     computed -- the value
         *                                     {@code WorldConflictScreen}'s
         *                                     "Content match" row compares
         *                                     against the cloud side's own
         *                                     content signature, replacing
         *                                     the previous raw-byte-size
         *                                     comparison
         */
        public record LocalDetail(
                String displayName,
                long lastModifiedMillis,
                long folderSizeBytes,
                String deviceLabel,
                Long ancestorSyncedAtTimestamp,
                String gameMode,
                long lastPlayedMillis,
                boolean hardcore,
                boolean cheatsEnabled,
                String difficulty,
                Long seed,
                String minecraftVersion,
                long dayCount,
                int regionFileCount,
                boolean levelDatReadable,
                String contentSignature) {
        }

        /**
         * cloud-world-metadata-file spec Requirement 4/6: extended in place
         * with the richer fields sourced from the new per-world Cloud
         * metadata file ({@code WorldCloudMetadata}) when present; every new
         * field below falls back to its documented sentinel when no metadata
         * file exists yet for this world (Compatibility -- an old world
         * synced before this feature shipped, or a metadata upload that
         * failed independently of the archive upload). {@code archiveSizeBytes}
         * remains informational-only per Requirement 6 -- it no longer drives
         * {@code WorldConflictScreen}'s match/mismatch coloring, which uses
         * {@link #contentSignature()} instead.
         *
         * @param displayName        the cloud fingerprint's player-facing
         *                           name
         * @param syncedAtTimestamp  the cloud fingerprint's
         *                           {@code syncedAtTimestamp}
         * @param archiveSizeBytes   the cloud archive's compressed size in
         *                           bytes, or {@code -1} if unavailable
         *                           ({@link WorldArchiveCloudStore#fileSize}
         *                           returned {@code -1})
         * @param deviceLabel        the device label of the current global
         *                           fingerprint (which device produced the
         *                           Cloud version)
         * @param lastPlayedMillis   the cloud metadata file's last-played
         *                           time, epoch millis, or {@code -1} if no
         *                           metadata file exists yet
         * @param minecraftVersion   the cloud metadata file's Minecraft
         *                           version, or {@code null} if unavailable
         * @param seed               the cloud metadata file's seed, or
         *                           {@code null} if unavailable
         * @param gameMode           the cloud metadata file's game mode, or
         *                           {@code null} if unavailable
         * @param difficulty         the cloud metadata file's difficulty, or
         *                           {@code null} if unavailable
         * @param hardcore           the cloud metadata file's hardcore flag,
         *                           or {@code false} if unavailable
         * @param contentSignature   the cloud metadata file's SHA-256
         *                           whole-folder content hash, or
         *                           {@code null} if no metadata file exists
         *                           yet -- the value {@code WorldConflictScreen}'s
         *                           "Content match" row compares against the
         *                           local side's own content signature
         */
        public record CloudDetail(
                String displayName,
                long syncedAtTimestamp,
                long archiveSizeBytes,
                String deviceLabel,
                long lastPlayedMillis,
                String minecraftVersion,
                Long seed,
                String gameMode,
                String difficulty,
                boolean hardcore,
                String contentSignature) {
        }
    }

    /**
     * cloud-sync-conflict-ux F10's batched {@code level.dat} NBT read,
     * performed once per screen-open by a platform's own {@code WorldsPanel}
     * (this module cannot itself open a {@code LevelStorageAccess}) and
     * passed into {@link #detailFor}. A plain data carrier -- no
     * Minecraft-client types -- so it is safe for this
     * Minecraft-client-type-free interface to reference.
     *
     * @param seed             the world's seed, or {@code null} if the read
     *                         failed
     * @param difficulty       the world's difficulty, pre-formatted for
     *                         display, or {@code null} if the read failed
     * @param cheatsEnabled    the world's cheats/{@code allowCommands} flag,
     *                         or {@code null} if the read failed
     * @param dayCount         the world's elapsed in-game day count, or
     *                         {@code -1} if the read failed
     * @param minecraftVersion the Minecraft version the world was last
     *                         played with, or {@code null} if unavailable
     * @param readable         {@code false} if the batched read failed
     *                         entirely (every other field is then a
     *                         meaningless sentinel)
     * @param lastPlayedMillis the world's last-played time, epoch millis,
     *                         from {@code level.dat}'s {@code LastPlayed} tag,
     *                         or {@code -1} if unavailable (mirrors
     *                         {@code CloudOnlyWorldSummary}'s own "-1 means
     *                         unavailable" convention)
     * @param gameMode         the world's game mode, pre-formatted for
     *                         display to match {@code LevelSummary.getGameMode()
     *                         .getTranslatableName().getString()}'s output, or
     *                         {@code null} if unavailable
     * @param hardcore         the world's hardcore flag, from {@code level.dat}'s
     *                         {@code hardcore} tag, or {@code false} if
     *                         unavailable (note: {@code false} is also a valid
     *                         real value, same caveat as
     *                         {@code CloudOnlyWorldSummary}'s own field)
     */
    record LevelDatBatch(
            Long seed,
            String difficulty,
            Boolean cheatsEnabled,
            long dayCount,
            String minecraftVersion,
            boolean readable,
            long lastPlayedMillis,
            String gameMode,
            boolean hardcore) {

        /** The sentinel batch used when a {@code level.dat} read is skipped/fails entirely. */
        public static LevelDatBatch unreadable() {
            return new LevelDatBatch(null, null, null, -1L, null, false, -1L, null, false);
        }
    }
}
