package de.lazuli.features.steamcloudsync.api;

/**
 * One entry in the FR6.6 Cloud metadata file: a fingerprint of one world this
 * feature has synced to Steam Cloud from some device, used both for
 * warn-before-overwrite conflict detection (FR6.6) and for cloud-only-world
 * detection (FR6.8).
 *
 * <p>Usage example:
 * <pre>{@code
 * WorldFingerprint fingerprint = new WorldFingerprint(
 *         "my_world_folder", "My World", "duck's PC", System.currentTimeMillis());
 * }</pre>
 *
 * @param worldSlug         the world's save-folder name
 * @param displayName       a player-facing name for the world, so a
 *                          cloud-only entry (with no local {@code level.dat})
 *                          has something to render
 * @param deviceLabel       a human-readable label for the device that last
 *                          synced this world (see {@code DeviceLabelResolver})
 * @param syncedAtTimestamp epoch-millis timestamp of the last Cloud sync for
 *                          this world
 */
public record WorldFingerprint(String worldSlug, String displayName, String deviceLabel, long syncedAtTimestamp) {
}
