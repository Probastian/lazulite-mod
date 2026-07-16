package de.lazuli.api.cloudsync;

/**
 * A plain, Minecraft-free stand-in for a Singleplayer world-select row that
 * has no real local save folder (and therefore no real
 * {@code LevelSummary}) backing it yet -- a world this device's Steam Cloud
 * fingerprint metadata (FR6.6) knows about, but that has never been restored
 * here (FR6.8).
 *
 * <p>Deliberately not any Minecraft type, so it stays constructible/testable
 * on a plain JVM; a platform Version Adapter is responsible for turning one
 * of these into an actual synthetic list-widget row (FR6.9).
 *
 * <p>Usage example:
 * <pre>{@code
 * List<CloudOnlyWorldSummary> cloudOnly = hook.listCloudOnlyWorlds(localFolders);
 * for (CloudOnlyWorldSummary summary : cloudOnly) {
 *     renderSyntheticRow(summary.displayName(), summary.deviceLabel(), summary.syncedAtTimestamp());
 * }
 * }</pre>
 *
 * @param worldSlug          the world's intended save-folder name (used to
 *                           key restore/collision checks)
 * @param displayName        a player-facing name for the world, since no
 *                           local {@code level.dat} exists yet to read one
 *                           from
 * @param deviceLabel        a human-readable label for whichever device last
 *                           synced this world, or {@code "Unknown device"}
 * @param syncedAtTimestamp  epoch-millis timestamp of the last Cloud sync for
 *                           this world
 */
public record CloudOnlyWorldSummary(String worldSlug, String displayName, String deviceLabel, long syncedAtTimestamp) {
}
