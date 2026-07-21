package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.WorldSyncStatusHook;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-only, in-memory tracker of each local world's last sync-attempt
 * outcome (FRU.3 of the "Diagnostics, Per-World Sync-Status UI, and Archive
 * Compression" companion spec), keyed by {@code worldSlug}. Constructed once
 * by the composition root and injected into {@link WorldSaveSyncService}
 * alongside its existing {@code warningLogger}/{@code playerNotifier}
 * collaborators; implements {@link WorldSyncStatusHook} so a platform Version
 * Adapter (the per-world status icon) can hold this class directly, mirroring
 * {@link WorldSyncPreferenceService}'s implementation of the sibling
 * {@code WorldSyncToggleHook}.
 *
 * <p>Backed by {@link ConcurrentHashMap} rather than
 * {@code WorldSyncPreferenceService}'s {@code synchronized}/{@code LinkedHashMap}
 * shape: {@link WorldSaveSyncService} writes into this tracker from both
 * {@code CloudSyncWorker}'s background thread and the client tick thread,
 * while the render-thread mixin reads it every frame -- a concurrent map
 * needs no external synchronization for these simple put/get accesses.
 *
 * <p>Not persisted across a client restart (Future Extensions of the
 * companion spec) -- every world starts each session as {@code NOT_SYNCED}
 * until a sync checkpoint actually runs for it.
 */
public final class WorldSyncStatusTracker implements WorldSyncStatusHook {

    private final ConcurrentHashMap<String, SyncStatus> statuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastErrors = new ConcurrentHashMap<>();

    /**
     * Records a successful sync for {@code worldSlug}, clearing any
     * previously recorded error text.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void markSynced(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        statuses.put(worldSlug, SyncStatus.SYNCED);
        lastErrors.remove(worldSlug);
    }

    /**
     * Records a failed sync attempt for {@code worldSlug}.
     *
     * @param worldSlug the world's on-disk save-folder name
     * @param message   a human-readable failure message; may be {@code null}
     */
    public void markError(String worldSlug, String message) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        statuses.put(worldSlug, SyncStatus.SYNC_ERROR);
        lastErrors.put(worldSlug, message == null ? "Unknown Steam Cloud sync failure." : message);
    }

    /**
     * Records a size-threshold-exceeded skip for {@code worldSlug}.
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    public void markSkippedTooLarge(String worldSlug) {
        Objects.requireNonNull(worldSlug, "worldSlug");
        statuses.put(worldSlug, SyncStatus.SKIPPED_TOO_LARGE);
        lastErrors.remove(worldSlug);
    }

    @Override
    public SyncStatus statusFor(String worldSlug) {
        return statuses.getOrDefault(worldSlug, SyncStatus.NOT_SYNCED);
    }

    @Override
    public String lastErrorFor(String worldSlug) {
        return statusFor(worldSlug) == SyncStatus.SYNC_ERROR ? lastErrors.get(worldSlug) : null;
    }
}
