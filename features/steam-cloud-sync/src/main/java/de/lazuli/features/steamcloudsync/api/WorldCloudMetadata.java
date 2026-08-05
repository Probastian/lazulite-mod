package de.lazuli.features.steamcloudsync.api;

/**
 * A small, per-world Cloud object uploaded independently of that world's
 * archive (never itself archived/compressed inside the zip), stored under
 * {@code lazuli-world-meta-<slug>.json} (mirrored by
 * {@code WorldSaveSyncService#metadataFileName(String)}). Carries richer
 * display metadata than {@link WorldFingerprint} alone (last-played time,
 * Minecraft version, seed, game mode, difficulty, hardcore, an optional
 * world icon) plus a real content-identity signal
 * ({@link #contentSignature()}, a SHA-256 whole-folder content hash) so a
 * conflict screen can tell "did the content actually change" apart from
 * "did the non-deterministic zip archive's compressed byte size change."
 *
 * <p>This is a companion to, not a replacement for, {@link WorldFingerprint}
 * (the existing all-worlds-in-one-file list used for warn-before-overwrite
 * conflict detection and the FR-V two-sided-conflict trigger) -- this
 * record is written/read one-per-world, alongside that file, never instead
 * of it.
 *
 * @param schemaVersion      this record's own JSON schema version (see
 *                           {@code WorldCloudMetadataIO.CURRENT_SCHEMA_VERSION}),
 *                           independent of {@code SteamCloudSyncConfig}'s own
 * @param worldSlug          the world's save-folder name
 * @param displayName        a player-facing name for the world
 * @param lastPlayedMillis   the world's last-played time, epoch millis
 * @param minecraftVersion   the Minecraft version the world was last played
 *                           with, or {@code null} if unavailable
 * @param seed               the world's seed, or {@code null} if unavailable
 * @param gameMode           the world's game mode, pre-formatted for display
 * @param difficulty         the world's difficulty, pre-formatted for
 *                           display, or {@code null} if the {@code level.dat}
 *                           read the metadata was built from failed/was
 *                           unavailable at that sync checkpoint
 * @param hardcore           the world's hardcore flag
 * @param contentSignature   a SHA-256 hex digest computed over the whole
 *                           world folder's content (every regular file's own
 *                           relative path + bytes), used to answer "is the
 *                           content actually different" independent of the
 *                           non-deterministic zip archive's compressed size
 * @param syncedAtTimestamp  epoch-millis timestamp of the sync checkpoint
 *                           this metadata file was written at, mirroring
 *                           {@link WorldFingerprint#syncedAtTimestamp()} so a
 *                           caller never needs both files just to know "when"
 * @param iconBase64         the world's {@code icon.png} bytes, Base64
 *                           encoded, or {@code null} if the world has no
 *                           custom icon
 */
public record WorldCloudMetadata(
        int schemaVersion,
        String worldSlug,
        String displayName,
        long lastPlayedMillis,
        String minecraftVersion,
        Long seed,
        String gameMode,
        String difficulty,
        boolean hardcore,
        String contentSignature,
        long syncedAtTimestamp,
        String iconBase64) {
}
