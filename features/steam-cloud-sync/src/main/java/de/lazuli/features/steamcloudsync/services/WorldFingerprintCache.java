package de.lazuli.features.steamcloudsync.services;

import de.lazuli.features.steamcloudsync.api.WorldFingerprint;

import java.util.List;

/**
 * A RAM-only, per-process holder of "what Steam Cloud's fingerprint file
 * currently says," shared by {@link WorldSaveSyncService} and
 * {@link CloudOnlyWorldsFacade}.
 *
 * <p>This deliberately holds no on-disk persistence of its own: Cloud's
 * current fingerprint state must always be re-fetched from Steam (via
 * {@link WorldSaveSyncService#pullFingerprints()}) at least once per process
 * lifetime before anything reads it, and must never survive a process
 * restart via a local file. The previous design persisted this same data to
 * a local {@code world-fingerprint-cache.json} file; that file could then be
 * dragged along by an external backup/restore of a whole run/config folder,
 * reviving a stale belief about Cloud's state that this device's own actual,
 * currently-running session never independently re-confirmed with Steam --
 * masking real conflicts (see {@code checkConflictFor}'s Javadoc and the
 * cloud-sync-status-ui spec/plan for the concrete repro). Keeping this
 * in-memory-only closes that gap: a fresh process always starts with an
 * empty cache and must re-pull from Steam before this data is trusted again.
 *
 * <p>The per-device "last known common ancestor" cache (F20e,
 * {@code world-sync-ancestor-cache.json}) is unaffected by this change and
 * remains on-disk local history -- it records what *this device itself*
 * last did, not a cached belief about Cloud's current state.
 */
public final class WorldFingerprintCache {

    private volatile List<WorldFingerprint> entries = List.of();

    /** @return the current in-memory snapshot of Cloud's fingerprint file, as of the last {@link #replaceAll} call. */
    public List<WorldFingerprint> entries() {
        return entries;
    }

    /** Replaces the entire in-memory snapshot. Never persisted to disk. */
    public void replaceAll(List<WorldFingerprint> newEntries) {
        this.entries = List.copyOf(newEntries);
    }
}
