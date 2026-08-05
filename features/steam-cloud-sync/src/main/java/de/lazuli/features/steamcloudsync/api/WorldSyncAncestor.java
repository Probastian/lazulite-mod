package de.lazuli.features.steamcloudsync.api;

/**
 * F20e's "last known common ancestor" marker: this device's own, locally-kept,
 * never-pushed-to-Cloud record of the {@link WorldFingerprint} state that was
 * in effect the last time <em>this</em> device itself completed a successful
 * sync of a given world.
 *
 * <p>Deliberately a 3-field subset of {@link WorldFingerprint} (no
 * {@code displayName} -- callers re-derive a display name from the live
 * fingerprint/world summary when rendering {@code WorldConflictScreen}).
 * Persisted locally only, in {@code world-sync-ancestor-cache.json}, never
 * written to {@code CloudFileStore} -- see
 * {@code WorldSaveSyncService#updateFingerprint}.
 *
 * @param worldSlug         the world's save-folder name
 * @param deviceLabel       this device's own label at the time it recorded
 *                          this ancestor entry (always equal to the owning
 *                          device's current {@code deviceLabel} by
 *                          construction -- kept explicit for symmetry with
 *                          {@link WorldFingerprint} and for straightforward
 *                          JSON round-tripping)
 * @param syncedAtTimestamp epoch-millis timestamp of this device's own last
 *                          successful sync of this world
 */
public record WorldSyncAncestor(String worldSlug, String deviceLabel, long syncedAtTimestamp) {
}
