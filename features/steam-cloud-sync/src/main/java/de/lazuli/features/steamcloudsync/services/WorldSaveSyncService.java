package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.WorldConflictHook;
import de.lazuli.api.cloudsync.WorldConflictResolutionHook;
import de.lazuli.api.cloudsync.WorldFreshnessHook;
import de.lazuli.api.cloudsync.WorldFreshnessHook.FreshnessDetail;
import de.lazuli.api.cloudsync.WorldConflictHook.ConflictStatus;
import de.lazuli.api.cloudsync.WorldConflictResolutionHook.ConflictDetail;
import de.lazuli.features.steamcloudsync.api.WorldCloudMetadata;
import de.lazuli.features.steamcloudsync.api.WorldFingerprint;
import de.lazuli.features.steamcloudsync.api.WorldSyncAncestor;
import de.lazuli.features.steamcloudsync.config.WorldCloudMetadataIO;
import de.lazuli.features.steamcloudsync.config.WorldFingerprintIO;
import de.lazuli.features.steamcloudsync.config.WorldSyncAncestorIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Group 6's world-save-sync business logic: the size check and
 * whole-archive-vs-selective-fallback decision (FR6.3-FR6.5), archive
 * building via {@code java.util.zip} (no new dependency), the fingerprint
 * conflict warning (FR6.6), and quota/{@code fileForget} bookkeeping (FR6.7).
 * Consults {@link WorldSyncPreferenceService} before acting on a given world
 * (FR6.2).
 *
 * <p>{@link #decideStrategy(long, int)} and
 * {@link #computeFolderSizeBytes(Path)} are pure/plain-JVM-testable (the
 * former needs no I/O at all; the latter only real, non-Steam
 * {@code java.nio.file} calls, testable with a {@code @TempDir}, mirroring
 * {@code HelloWorldMainMenuConfigIOTest}'s own precedent). Every Cloud call
 * goes through the injected {@link WorldArchiveCloudStore}/
 * {@link CloudFileStore} seams -- never {@code com.codedisaster.steamworks.*}
 * directly (NFR1).
 *
 * <p>Usage example (from the platform composition root, wired to the FR6.2
 * world-unload checkpoint):
 * <pre>{@code
 * service.onWorldUnload("my_world_folder", worldFolderPath, "My World");
 * }</pre>
 */
public final class WorldSaveSyncService implements WorldFreshnessHook, WorldConflictHook, WorldConflictResolutionHook {

    /**
     * The hardcoded, non-configurable Group 6 world-archive size threshold
     * (megabytes). Deliberately never read from or written to any on-disk
     * config file -- this is "the new truth" per the cloud-sync-threshold
     * spec's decision: a future change to this constant simply changes
     * behavior on next launch, with no stale on-disk value ever able to
     * override or shadow it. Do not reintroduce this as a
     * {@code SteamCloudSyncConfig} field.
     */
    public static final int MAX_WORLD_ARCHIVE_SIZE_MB = 1024;

    private static final String FINGERPRINT_CLOUD_FILE_NAME = "lazuli-world-fingerprints.json";

    /** The whole-archive-vs-skip decision (FR6.3-FR6.5). Sync is strictly all-or-nothing. */
    public enum SyncStrategy {
        WHOLE_ARCHIVE,
        SKIPPED
    }

    private final WorldArchiveCloudStore archiveStore;
    private final CloudFileStore cloudFileStore;
    private final WorldSyncPreferenceService preferenceService;
    private final CloudSyncWorker worker;
    private final WorldFingerprintCache fingerprintCache;
    private final String deviceLabel;
    private final int maxWorldArchiveSizeMb;
    private final Consumer<String> warningLogger;
    private final Consumer<String> playerNotifier;
    private final WorldSyncStatusTracker statusTracker;
    private final Path ancestorCachePath;
    private final WorldFingerprintIO fingerprintIO = new WorldFingerprintIO();
    private final WorldSyncAncestorIO ancestorIO = new WorldSyncAncestorIO();
    private final WorldCloudMetadataIO metadataIO = new WorldCloudMetadataIO();
    private final WorldCloudMigrationService migrationService;

    /**
     * @param archiveStore           the Group 6 Cloud seam (real or no-op)
     * @param cloudFileStore         the small-file Cloud seam, used only for
     *                               the fingerprint metadata file
     * @param preferenceService      the per-world sync-preference service
     *                               (FR6.1/FR6.2)
     * @param worker                 hops compression/archive-building onto a
     *                               background thread and the actual Steam
     *                               calls back onto the client tick thread
     * @param fingerprintCache       this process's RAM-only, never-persisted
     *                               snapshot of Cloud's current fingerprint
     *                               file, populated by {@link #pullFingerprints()}
     *                               -- deliberately not backed by any local
     *                               file, so it cannot be dragged along by an
     *                               external backup/restore of the run
     *                               folder and must always be re-fetched from
     *                               Steam each process lifetime
     * @param ancestorCachePath      this device's local-only, never-pushed
     *                               "last known common ancestor" cache
     *                               (F20e), used only for FR-V's two-sided
     *                               conflict detection
     * @param deviceLabel            this device's own label (see
     *                               {@link DeviceLabelResolver})
     * @param maxWorldArchiveSizeMb  the size threshold (FR6.3), from the
     *                               hardcoded {@link #MAX_WORLD_ARCHIVE_SIZE_MB}
     *                               constant (never config-sourced)
     * @param warningLogger          receives a human-readable message for any
     *                               internal failure; never invoked with a
     *                               thrown exception
     * @param playerNotifier         receives a human-readable,
     *                               player-visible message for the FR6.4/
     *                               FR6.6/FR6.7 notifications this service
     *                               must surface
     * @param statusTracker          receives per-world sync-status updates at
     *                               the same checkpoints
     *                               {@code warningLogger}/{@code playerNotifier}
     *                               are already called (FRU.3 of the
     *                               diagnostics/UI/compression companion spec)
     */
    public WorldSaveSyncService(
            WorldArchiveCloudStore archiveStore,
            CloudFileStore cloudFileStore,
            WorldSyncPreferenceService preferenceService,
            CloudSyncWorker worker,
            WorldFingerprintCache fingerprintCache,
            Path ancestorCachePath,
            String deviceLabel,
            int maxWorldArchiveSizeMb,
            Consumer<String> warningLogger,
            Consumer<String> playerNotifier,
            WorldSyncStatusTracker statusTracker,
            WorldCloudMigrationService migrationService) {
        this.migrationService = Objects.requireNonNull(migrationService, "migrationService");
        this.archiveStore = Objects.requireNonNull(archiveStore, "archiveStore");
        this.cloudFileStore = Objects.requireNonNull(cloudFileStore, "cloudFileStore");
        this.preferenceService = Objects.requireNonNull(preferenceService, "preferenceService");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.fingerprintCache = Objects.requireNonNull(fingerprintCache, "fingerprintCache");
        this.ancestorCachePath = Objects.requireNonNull(ancestorCachePath, "ancestorCachePath");
        this.deviceLabel = Objects.requireNonNull(deviceLabel, "deviceLabel");
        this.maxWorldArchiveSizeMb = maxWorldArchiveSizeMb;
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.playerNotifier = Objects.requireNonNull(playerNotifier, "playerNotifier");
        this.statusTracker = Objects.requireNonNull(statusTracker, "statusTracker");
    }

    /**
     * cloud-sync-uuid-identity FR1.4/FR6.1: the zero-Cloud-I/O key resolution
     * used by every read-only status-query method
     * ({@link #upToDateStatusFor}/{@link #upToDateStatusDetailFor}/
     * {@link #checkConflictFor}/{@link #detailFor}/{@link #cloudMetadataFor}).
     * These methods are reachable synchronously from the client/render
     * thread (a platform {@code WorldsPanel} calls
     * {@link WorldFreshnessHook}/{@link WorldConflictHook} directly, not via
     * {@link CloudSyncWorker}), so they must never trigger real Phase A
     * migration I/O (FR2.5 forbids that off the background thread) --
     * instead they use {@link WorldCloudMigrationService#existingCloudWorldId}
     * (FR1.2/FR1.3's synchronous, zero-I/O lookup), falling back to
     * {@code worldSlug} itself unresolved when no migration has started yet
     * for this folder (matching this method's pre-migration behavior for a
     * world that has never been Cloud-synced by this feature).
     */
    private String resolveForRead(String worldSlug) {
        return migrationService.existingCloudWorldId(worldSlug).map(java.util.UUID::toString).orElse(worldSlug);
    }

    /**
     * The FR6.2 trigger: called on world unload/exit. A no-op unless this
     * world's per-world preference is enabled. Archive building runs on the
     * background thread; only the final Steam call hops back onto the
     * client tick thread (Architecture -- Threading).
     *
     * @param worldSlug   the world's save-folder name
     * @param worldFolder the world's on-disk save folder
     * @param displayName a player-facing name for the world
     */
    public void onWorldUnload(String worldSlug, Path worldFolder, String displayName) {
        onWorldUnload(worldSlug, worldFolder, displayName, WorldConflictResolutionHook.LevelDatBatch::unreadable);
    }

    /**
     * Overload of {@link #onWorldUnload(String, Path, String)} that also
     * accepts a lazily-evaluated {@code level.dat} NBT batch (per the
     * cloud-world-metadata-file spec's Architecture section), read only once
     * this checkpoint's background work actually runs -- never on the
     * calling (client tick) thread. The platform composition root supplies
     * this at the {@code onWorldUnload} checkpoint since the world has
     * already been unloaded/disconnected by the time this fires, making a
     * fresh {@code LevelStorageAccess} read safe there (unlike the
     * still-loaded-world {@link #onWorldSaved} checkpoint, which keeps using
     * the sentinel {@link WorldConflictResolutionHook.LevelDatBatch#unreadable()}
     * via {@link #onWorldSaved(String, Path, String)} -- see Risks in the
     * implementation plan for why this is not currently safe there).
     *
     * @param worldSlug            the world's save-folder name
     * @param worldFolder          the world's on-disk save folder
     * @param displayName          a player-facing name for the world
     * @param levelDatBatchSupplier supplies the batched {@code level.dat}
     *                              read for the new per-world Cloud metadata
     *                              file; invoked on the background worker
     */
    public void onWorldUnload(String worldSlug, Path worldFolder, String displayName,
            Supplier<WorldConflictResolutionHook.LevelDatBatch> levelDatBatchSupplier) {
        if (!preferenceService.isSyncEnabled(worldSlug)) {
            return;
        }
        statusTracker.markUploadPending(worldSlug);
        worker.submitBackgroundWork(() -> syncWorldNow(worldSlug, worldFolder, displayName, levelDatBatchSupplier.get()));
    }

    /**
     * The FR-T.1 mid-session save hook: called from the new
     * {@code WorldSaveHookMixin} whenever the integrated server finishes an
     * on-disk save, without unloading the world. A no-op unless this world's
     * per-world preference is enabled, and re-entrancy-guarded so a save that
     * lands mid-upload for the same world does not queue a second one.
     *
     * @param worldSlug   the world's save-folder name
     * @param worldFolder the world's on-disk save folder
     * @param displayName a player-facing name for the world
     */
    public void onWorldSaved(String worldSlug, Path worldFolder, String displayName) {
        if (!preferenceService.isSyncEnabled(worldSlug)) {
            return;
        }
        if (statusTracker.isUploadInProgress(worldSlug)) {
            return;
        }
        // FR-V.6: don't race a pending, unresolved WorldConflictScreen decision.
        if (statusTracker.hasPendingConflict(worldSlug)) {
            return;
        }
        statusTracker.markUploadPending(worldSlug);
        worker.submitBackgroundWork(() -> syncWorldNow(worldSlug, worldFolder, displayName));
    }

    /**
     * The FR-T.2 startup checkpoint: uploads only worlds whose local save
     * folder has changed since this device's own last successful upload
     * (i.e. found {@link UpToDateStatus#STALE} via case (a), local newer than
     * this device's own fingerprint) -- never every world unconditionally,
     * and never a world that is stale only because a different device
     * synced it more recently (case (b)) or one with no fingerprint at all
     * ({@link UpToDateStatus#UNKNOWN}).
     *
     * @param knownWorlds the worlds to consider
     */
    public void checkAndUploadStaleWorldsAtStartup(List<KnownWorld> knownWorlds) {
        // cloud-sync-uuid-identity FR2.2: this checkpoint is definitionally
        // reached only at client startup (no world loaded yet), so it is one
        // of the two qualifying Phase B (physical rename) checkpoints.
        migrationService.runPendingRenames();
        for (KnownWorld world : knownWorlds) {
            if (!preferenceService.isSyncEnabled(world.worldSlug())) {
                continue;
            }
            // FR-V.6: don't race a pending, unresolved WorldConflictScreen decision.
            if (statusTracker.hasPendingConflict(world.worldSlug())) {
                continue;
            }
            if (isLocallyStale(world.worldSlug(), world.worldFolder())) {
                statusTracker.markUploadPending(world.worldSlug());
                // cloud-world-metadata-file gap fix: left on the sentinel
                // LevelDatBatch here rather than wired to a real read like
                // onWorldUnload -- unlike that checkpoint, wiring a real
                // read through here would require adding a
                // Supplier<LevelDatBatch>-per-KnownWorld parameter to this
                // public method (and to KnownWorld's construction at all
                // three platform composition roots), a signature change
                // beyond this targeted gap fix's scope rather than a
                // same-shape drop-in. No world is loaded at this startup
                // checkpoint, so a future follow-up wiring this through is
                // expected to be safe.
                worker.submitBackgroundWork(() -> syncWorldNow(world.worldSlug(), world.worldFolder(), world.displayName()));
            }
        }
    }

    /**
     * Gap 2 (sync-conflict-coverage-gaps spec): the toggle-on checkpoint --
     * wired by the platform composition root as
     * {@code WorldSyncPreferenceService}'s {@code onSyncEnabledListener}, so
     * this runs immediately after a world's sync preference flips from
     * disabled to enabled. Marks the transient "conflict check pending"
     * state synchronously (so {@code WorldsPanel} can block Play/Edit from
     * the moment this method returns), then runs the strict
     * {@link #checkConflictFor(String, Path)} asynchronously on the
     * background worker -- never inline, since it does Cloud I/O.
     *
     * <p>On {@code ConflictStatus.CONFLICT}: {@code checkConflictFor} itself
     * already calls {@code statusTracker.markConflictPending}; this method
     * additionally does <strong>not</strong> proceed to
     * {@link #syncWorldNow}, leaving the existing Conflict UX
     * (resolve pill/{@code WorldConflictScreen}) as the only way forward.
     *
     * <p>On a non-conflict result: proceeds with the same
     * mark-pending-then-sync sequence {@link #onWorldUnload} already uses,
     * just already running on the background thread rather than needing a
     * fresh {@code submitBackgroundWork} hop.
     *
     * @param worldSlug   the world's save-folder name
     * @param worldFolder the world's on-disk save folder
     * @param displayName a player-facing name for the world
     */
    public void handleSyncReenabled(String worldSlug, Path worldFolder, String displayName) {
        statusTracker.markConflictCheckPending(worldSlug);
        worker.submitBackgroundWork(() -> {
            try {
                ConflictStatus status = checkConflictFor(worldSlug, worldFolder);
                if (status == ConflictStatus.CONFLICT) {
                    return;
                }
                statusTracker.markUploadPending(worldSlug);
                // cloud-world-metadata-file gap fix: same rationale as
                // checkAndUploadStaleWorldsAtStartup -- this checkpoint only
                // fires for a world that is not currently loaded (toggled on
                // from WorldsPanel at the main menu), so a real level.dat
                // read would likely be safe here too, but wiring it through
                // would require this public method to also accept a
                // Supplier<LevelDatBatch> (and every platform composition
                // root's onSyncEnabledListener wiring to supply one), which
                // is beyond this targeted gap fix's scope.
                syncWorldNow(worldSlug, worldFolder, displayName);
            } finally {
                statusTracker.clearConflictCheckPending(worldSlug);
                // cloud-sync-uuid-identity FR2.2: handleSyncReenabled only
                // ever fires from the Worlds-tab main-menu screen, mutually
                // exclusive with a loaded world -- the second qualifying
                // Phase B (physical rename) checkpoint. Run after this
                // world's own Phase A/sync attempt above so a freshly-toggled
                // world's folder is renamed essentially immediately (FR2.3).
                migrationService.runPendingRenames();
            }
        });
    }

    /**
     * Request 3 (cloud-sync-threshold-and-full-sync-only): the
     * enabled->disabled un-sync checkpoint -- wired by the platform
     * composition root as {@code WorldSyncPreferenceService}'s
     * {@code onSyncDisabledListener}, so this runs immediately after a
     * world's sync preference flips from enabled to disabled. Deletes the
     * world's Cloud archive (if any) via {@code fileDelete} semantics
     * (freeing quota deterministically, unlike {@code forget}), always run
     * on the background worker since this is Cloud I/O.
     *
     * <p>On success: removes this world's entry from the RAM-only
     * fingerprint cache and clears its tracked status, so it stops
     * appearing as Cloud-backed to other devices/the freshness UI.
     *
     * <p>On failure: logs and notifies the player, but does <strong>not</strong>
     * re-enable the sync preference or otherwise roll back the toggle -- the
     * local preference-disable already succeeded and stands regardless of
     * whether this best-effort Cloud cleanup succeeds.
     *
     * @param worldSlug   the world's save-folder name
     * @param displayName a player-facing name for the world
     */
    public void handleSyncDisabled(String worldSlug, String displayName) {
        worker.submitBackgroundWork(() -> {
            // cloud-sync-uuid-identity FR1.5: un-syncing never undoes
            // migration and never triggers a new one -- a zero-I/O read
            // resolution is enough here (this world was already synced at
            // least once for this checkpoint to even be reachable).
            String cloudWorldId = resolveForRead(worldSlug);
            boolean deleted = archiveStore.deleteWorldArchive(archiveFileName(cloudWorldId));
            if (deleted) {
                List<WorldFingerprint> fingerprints = new ArrayList<>(readLocalFingerprintCache());
                fingerprints.removeIf(fingerprint -> fingerprint.worldSlug().equals(cloudWorldId));
                fingerprintCache.replaceAll(fingerprints);
                cloudFileStore.write(FINGERPRINT_CLOUD_FILE_NAME,
                        fingerprintIO.serialize(fingerprints).getBytes(StandardCharsets.UTF_8));
                statusTracker.clearStatus(worldSlug);
                // cloud-world-metadata-file Requirement 10: best-effort, not
                // itself gating anything further -- the archive delete above
                // is this method's primary success signal; a metadata-delete
                // failure here is logged only, not surfaced as a second
                // player-facing failure message for one un-sync action.
                if (!deleteCloudMetadata(cloudWorldId)) {
                    warningLogger.accept("Failed to delete Steam Cloud metadata file for world \"" + displayName
                            + "\" after successfully deleting its archive; it may be left orphaned on Cloud.");
                }
            } else {
                String message = "Failed to remove world \"" + displayName + "\" from Steam Cloud; it may still be "
                        + "taking up Cloud storage. You can try turning sync off again to retry.";
                warningLogger.accept(message);
                playerNotifier.accept(message);
            }
        });
    }

    /**
     * A tiny, Minecraft-free triple describing a known local world, used by
     * {@link #onWorldUnload}/{@link #onWorldSaved}/
     * {@link #checkAndUploadStaleWorldsAtStartup} alike, so this
     * platform-independent service never depends on any
     * {@code SingleplayerWorldInfo}/{@code LevelSummary}-style platform type.
     *
     * @param worldSlug   the world's save-folder name
     * @param worldFolder the world's on-disk save folder
     * @param displayName a player-facing name for the world
     */
    public record KnownWorld(String worldSlug, Path worldFolder, String displayName) {
    }

    /**
     * {@link WorldFreshnessHook}'s {@code String}-path contract, for a
     * platform Version Adapter -- delegates to
     * {@link #upToDateStatusFor(String, Path)}.
     */
    @Override
    public UpToDateStatus upToDateStatusFor(String worldSlug, String worldFolderAbsolutePath) {
        return upToDateStatusFor(worldSlug, java.nio.file.Paths.get(worldFolderAbsolutePath));
    }

    /**
     * Computes the most recent last-modified time of any regular file under
     * {@code worldFolder} (FR-P3), mirroring
     * {@link #computeFolderSizeBytes(Path)}'s existing {@code Files.walk}
     * shape.
     *
     * @param worldFolder the world's on-disk save folder
     * @return the maximum last-modified time, in epoch millis, of any
     *         regular file under {@code worldFolder}; {@code 0L} if the
     *         folder contains no regular files
     * @throws IOException if the folder cannot be walked
     */
    public static long computeFolderLastModifiedMillis(Path worldFolder) throws IOException {
        try (Stream<Path> stream = Files.walk(worldFolder)) {
            long max = 0L;
            for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                long millis = Files.getLastModifiedTime(path).toMillis();
                if (millis > max) {
                    max = millis;
                }
            }
            return max;
        }
    }

    /**
     * The FR-P3 freshness classification for a single world, computed
     * entirely from this device's own {@code WorldFingerprint} cache and one
     * local filesystem scan -- no new Steam API calls.
     *
     * @param worldSlug   the world's save-folder name
     * @param worldFolder the world's on-disk save folder
     * @return the classification for this world right now
     */
    public UpToDateStatus upToDateStatusFor(String worldSlug, Path worldFolder) {
        String cloudWorldId = resolveForRead(worldSlug);
        WorldFingerprint fingerprint = readLocalFingerprintCache().stream()
                .filter(entry -> entry.worldSlug().equals(cloudWorldId))
                .findFirst()
                .orElse(null);
        if (fingerprint == null) {
            return UpToDateStatus.UNKNOWN;
        }
        if (!fingerprint.deviceLabel().equals(deviceLabel)) {
            return UpToDateStatus.STALE;
        }
        long localLastModified;
        try {
            localLastModified = computeFolderLastModifiedMillis(worldFolder);
        } catch (IOException e) {
            warningLogger.accept("Failed to compute local last-modified time for world \"" + worldSlug + "\": " + e);
            return UpToDateStatus.UNKNOWN;
        }
        return localLastModified > fingerprint.syncedAtTimestamp() ? UpToDateStatus.STALE : UpToDateStatus.UP_TO_DATE;
    }

    /**
     * {@link WorldFreshnessHook}'s {@code String}-path FR-U.1 tooltip-detail
     * contract -- delegates to {@link #upToDateStatusDetailFor(String, Path)}.
     */
    @Override
    public FreshnessDetail upToDateStatusDetailFor(String worldSlug, String worldFolderAbsolutePath) {
        return upToDateStatusDetailFor(worldSlug, java.nio.file.Paths.get(worldFolderAbsolutePath));
    }

    /**
     * FR-U.1's richer, tooltip-oriented variant of {@link #upToDateStatusFor}:
     * the same classification, plus the concrete timestamps/device label
     * backing it, computed from data this method already reads for
     * {@link #upToDateStatusFor} (no new computation, F19).
     *
     * @param worldSlug   the world's save-folder name
     * @param worldFolder the world's on-disk save folder
     * @return this world's current classification, plus detail
     */
    public FreshnessDetail upToDateStatusDetailFor(String worldSlug, Path worldFolder) {
        String cloudWorldId = resolveForRead(worldSlug);
        WorldFingerprint fingerprint = readLocalFingerprintCache().stream()
                .filter(entry -> entry.worldSlug().equals(cloudWorldId))
                .findFirst()
                .orElse(null);
        if (fingerprint == null) {
            return new FreshnessDetail(UpToDateStatus.UNKNOWN, null, -1L, -1L, -1L);
        }
        if (!fingerprint.deviceLabel().equals(deviceLabel)) {
            return new FreshnessDetail(UpToDateStatus.STALE, fingerprint.deviceLabel(), fingerprint.syncedAtTimestamp(), -1L, -1L);
        }
        long localLastModified;
        try {
            localLastModified = computeFolderLastModifiedMillis(worldFolder);
        } catch (IOException e) {
            warningLogger.accept("Failed to compute local last-modified time for world \"" + worldSlug + "\": " + e);
            return new FreshnessDetail(UpToDateStatus.UNKNOWN, null, -1L, -1L, -1L);
        }
        UpToDateStatus status = localLastModified > fingerprint.syncedAtTimestamp() ? UpToDateStatus.STALE : UpToDateStatus.UP_TO_DATE;
        return new FreshnessDetail(status, null, -1L, localLastModified, fingerprint.syncedAtTimestamp());
    }

    /**
     * {@link WorldConflictHook}'s {@code String}-path contract -- delegates
     * to {@link #checkConflictFor(String, Path)}.
     */
    @Override
    public ConflictStatus checkConflictFor(String worldSlug, String worldFolderAbsolutePath) {
        return checkConflictFor(worldSlug, java.nio.file.Paths.get(worldFolderAbsolutePath));
    }

    /**
     * The FR-V.2/F20e true two-sided-conflict classification for a single
     * world: local diverged since this device's own last known sync
     * ({@code ownAncestor}) <strong>and</strong> the current global
     * fingerprint's {@code (deviceLabel, syncedAtTimestamp)} pair differs
     * from {@code ownAncestor}. A world with no {@code ownAncestor} entry
     * yet (never synced by this device post-upgrade, or ever) is always
     * {@code NONE} -- an accepted migration gap (see spec/plan Risks), never
     * retroactively flagged.
     *
     * <p>Deliberately does <strong>not</strong> additionally require
     * {@code globalFingerprint.deviceLabel() != deviceLabel} (an earlier
     * version of this check did): in the normal, untampered-with flow, this
     * device's own {@code ownAncestor} entry is always rewritten in lockstep
     * with the global fingerprint on every one of this device's own uploads
     * ({@link #updateFingerprint}), so the two can never actually diverge
     * while still sharing the same {@code deviceLabel} -- the exclusion was
     * therefore never load-bearing for that path (case (a) one-sided
     * staleness never reaches this branch: {@code globalFingerprint} and
     * {@code ownAncestor} are identical in that case, so
     * {@code cloudDiverged} is already {@code false}). It was, however,
     * actively harmful for a real scenario the exclusion masked: an
     * external restore of an entire backed-up run/config folder (world
     * files + the local fingerprint cache + the local ancestor cache all at
     * once) can bring back an internally-consistent-but-stale local
     * snapshot from *before* this same device's own later upload(s). In
     * that case {@code ownAncestor} legitimately no longer matches the true
     * current global fingerprint even though both still carry this
     * device's own {@code deviceLabel} -- and that mismatch is exactly the
     * "Cloud moved since my last known sync" signal this method exists to
     * catch, so it must not be suppressed just because the label happens to
     * match.
     *
     * @param worldSlug   the world's save-folder name
     * @param worldFolder the world's on-disk save folder
     * @return this world's current conflict classification
     */
    public ConflictStatus checkConflictFor(String worldSlug, Path worldFolder) {
        String cloudWorldId = resolveForRead(worldSlug);
        WorldSyncAncestor ownAncestor = readLocalAncestorCache().stream()
                .filter(entry -> entry.worldSlug().equals(cloudWorldId))
                .findFirst()
                .orElse(null);
        if (ownAncestor == null) {
            return ConflictStatus.NONE;
        }
        WorldFingerprint globalFingerprint = readLocalFingerprintCache().stream()
                .filter(entry -> entry.worldSlug().equals(cloudWorldId))
                .findFirst()
                .orElse(null);
        if (globalFingerprint == null) {
            return ConflictStatus.NONE;
        }
        boolean cloudDiverged = !globalFingerprint.deviceLabel().equals(ownAncestor.deviceLabel())
                || globalFingerprint.syncedAtTimestamp() != ownAncestor.syncedAtTimestamp();
        if (!cloudDiverged) {
            return ConflictStatus.NONE;
        }
        long localLastModified;
        try {
            localLastModified = computeFolderLastModifiedMillis(worldFolder);
        } catch (IOException e) {
            warningLogger.accept("Failed to compute local last-modified time for world \"" + worldSlug + "\": " + e);
            return ConflictStatus.NONE;
        }
        boolean localDiverged = localLastModified > ownAncestor.syncedAtTimestamp();
        if (!localDiverged) {
            return ConflictStatus.NONE;
        }
        // FR-V.6: mark pending here (rather than requiring the caller/UI to)
        // so the automatic upload trigger paths are guarded from the moment
        // a conflict is first detected at any checkpoint, not only once the
        // player has opened WorldConflictScreen.
        statusTracker.markConflictPending(worldSlug);
        return ConflictStatus.CONFLICT;
    }

    @Override
    public ConflictDetail detailFor(String worldSlug, String worldFolderAbsolutePath, String displayName,
            String gameModeDisplayName, long lastPlayedMillis, boolean hardcore,
            WorldConflictResolutionHook.LevelDatBatch levelDatBatch) {
        String cloudWorldId = resolveForRead(worldSlug);
        WorldFingerprint fingerprint = readLocalFingerprintCache().stream()
                .filter(entry -> entry.worldSlug().equals(cloudWorldId))
                .findFirst()
                .orElse(null);
        if (fingerprint == null) {
            return null;
        }
        Path worldFolder = java.nio.file.Paths.get(worldFolderAbsolutePath);
        long localLastModified;
        long localSizeBytes;
        try {
            localLastModified = computeFolderLastModifiedMillis(worldFolder);
            localSizeBytes = computeFolderSizeBytes(worldFolder);
        } catch (IOException e) {
            warningLogger.accept("Failed to read local world folder for conflict detail of \"" + worldSlug + "\": " + e);
            return null;
        }
        Long ancestorSyncedAtTimestamp = readLocalAncestorCache().stream()
                .filter(entry -> entry.worldSlug().equals(cloudWorldId))
                .findFirst()
                .map(WorldSyncAncestor::syncedAtTimestamp)
                .orElse(null);
        int regionFileCount = computeRegionFileCount(worldFolder);
        // Requirement 6: the local side's own content-identity signal, computed
        // the same way the Cloud metadata file's contentSignature is (reusing
        // the same Files.walk-based hash), so WorldConflictScreen's "Content
        // match" row compares like-for-like instead of local folder bytes vs.
        // the non-deterministic compressed archive's byte size.
        String localContentSignature;
        try {
            localContentSignature = computeContentSignature(worldFolder);
        } catch (IOException e) {
            warningLogger.accept("Failed to compute local content signature for conflict detail of \"" + worldSlug + "\": " + e);
            localContentSignature = null;
        }

        ConflictDetail.LocalDetail local = new ConflictDetail.LocalDetail(
                displayName,
                localLastModified,
                localSizeBytes,
                deviceLabel,
                ancestorSyncedAtTimestamp,
                gameModeDisplayName,
                lastPlayedMillis,
                hardcore,
                Boolean.TRUE.equals(levelDatBatch.cheatsEnabled()),
                levelDatBatch.difficulty(),
                levelDatBatch.seed(),
                levelDatBatch.minecraftVersion(),
                levelDatBatch.dayCount(),
                regionFileCount,
                levelDatBatch.readable(),
                localContentSignature);

        long archiveSizeBytes = archiveStore.fileSize(archiveFileName(cloudWorldId));
        WorldCloudMetadata metadata = cloudMetadataFor(cloudWorldId).orElse(null);
        ConflictDetail.CloudDetail cloud = metadata != null
                ? new ConflictDetail.CloudDetail(
                        fingerprint.displayName(), fingerprint.syncedAtTimestamp(), archiveSizeBytes, fingerprint.deviceLabel(),
                        metadata.lastPlayedMillis(), metadata.minecraftVersion(), metadata.seed(), metadata.gameMode(),
                        metadata.difficulty(), metadata.hardcore(), metadata.contentSignature())
                : new ConflictDetail.CloudDetail(
                        // Compatibility: no metadata file yet (old world/failed upload) --
                        // fall back to exactly today's fingerprint+archive-size-only shape,
                        // with every metadata-only field at its documented sentinel.
                        fingerprint.displayName(), fingerprint.syncedAtTimestamp(), archiveSizeBytes, fingerprint.deviceLabel(),
                        -1L, null, null, null, null, false, null);

        return new ConflictDetail(worldSlug, local, cloud);
    }

    /**
     * F10's region-file-count proxy for "explored area" -- a cheap
     * {@code Files.list} on the world folder's own {@code region}
     * subdirectory, independent of the {@code level.dat} NBT batch (needs no
     * {@code LevelStorageAccess}).
     *
     * @param worldFolder the world's on-disk save folder
     * @return the number of {@code .mca} files under {@code worldFolder/region},
     *         or {@code -1} if that directory does not exist/could not be listed
     */
    private int computeRegionFileCount(Path worldFolder) {
        Path regionDir = worldFolder.resolve("region");
        if (!Files.isDirectory(regionDir)) {
            return -1;
        }
        try (Stream<Path> stream = Files.list(regionDir)) {
            return (int) stream.filter(p -> p.toString().endsWith(".mca")).count();
        } catch (IOException e) {
            warningLogger.accept("Failed to list region directory " + regionDir + ": " + e);
            return -1;
        }
    }

    @Override
    public void resolveKeepLocal(String worldSlug, String worldFolderAbsolutePath, String displayName) {
        // FR-V.4: an explicit local-wins re-upload (never a silent Cloud pull) --
        // the resulting updateFingerprint call also records this device's new
        // ancestor, closing the conflict.
        statusTracker.markUploadPending(worldSlug);
        // cloud-world-metadata-file gap fix: left on the sentinel
        // LevelDatBatch here -- this checkpoint's only caller,
        // WorldConflictScreen's "Keep Local" button, already has a real
        // LevelDatBatch in hand (read once by the caller's own WorldsPanel
        // for detailFor's screen content), but plumbing it through to here
        // would require changing this method's public
        // WorldConflictResolutionHook#resolveKeepLocal interface signature
        // across every platform's WorldConflictScreen, which is beyond this
        // targeted gap fix's scope. The world is not loaded at this
        // checkpoint either, so this is expected to be safe to wire in a
        // future follow-up.
        worker.submitBackgroundWork(() ->
                syncWorldNow(worldSlug, java.nio.file.Paths.get(worldFolderAbsolutePath), displayName));
    }

    @Override
    public void recordKeepCloudResolution(String worldSlug, String cloudDeviceLabel, long cloudSyncedAtTimestamp) {
        writeAncestorEntry(resolveForRead(worldSlug), cloudDeviceLabel, cloudSyncedAtTimestamp);
        statusTracker.clearPendingConflict(worldSlug);
    }

    @Override
    public void clearPendingConflict(String worldSlug) {
        statusTracker.clearPendingConflict(worldSlug);
    }

    /**
     * The FR-T.2 "local newer than this device's own last upload" check
     * (case (a) only) -- used to decide which worlds to re-upload at
     * startup, deliberately narrower than {@link #upToDateStatusFor} (which
     * also reports case (b)/{@code UNKNOWN} as non-up-to-date, neither of
     * which should trigger a re-upload here).
     */
    private boolean isLocallyStale(String worldSlug, Path worldFolder) {
        // Safe to fully resolve (not just resolveForRead) here: every caller
        // of this method already runs on the background worker thread
        // (checkAndUploadStaleWorldsAtStartup is itself always invoked
        // inside a submitBackgroundWork lambda by CloudSyncCoordinator).
        String cloudWorldId = migrationService.resolveCloudWorldId(worldSlug).toString();
        WorldFingerprint fingerprint = readLocalFingerprintCache().stream()
                .filter(entry -> entry.worldSlug().equals(cloudWorldId))
                .findFirst()
                .orElse(null);
        if (fingerprint == null || !fingerprint.deviceLabel().equals(deviceLabel)) {
            return false;
        }
        try {
            return computeFolderLastModifiedMillis(worldFolder) > fingerprint.syncedAtTimestamp();
        } catch (IOException e) {
            warningLogger.accept("Failed to compute local last-modified time for world \"" + worldSlug + "\": " + e);
            return false;
        }
    }

    /**
     * Pulls the Cloud fingerprint file fresh from Steam into the RAM-only
     * {@link #fingerprintCache}. Call at least once per process, at the
     * client-startup checkpoint (FR0.3) and again at every other checkpoint
     * that needs an up-to-date view of Cloud's state (return to main menu,
     * Worlds-tab reload) -- this cache is never written to disk, so unlike
     * the previous local-file-backed design, it cannot be revived stale by
     * an external restore of an old run/config-folder backup; every process
     * lifetime starts empty and must re-earn this data from Steam.
     */
    public void pullFingerprints() {
        Optional<byte[]> bytesRead = cloudFileStore.read(FINGERPRINT_CLOUD_FILE_NAME);
        if (bytesRead.isEmpty()) {
            // No fingerprint file on Cloud yet (fresh installation, Steam
            // unavailable, or nothing has ever been synced) is an expected,
            // routine state -- not an internal failure -- so warningLogger
            // (reserved for actual failures; see the constructor Javadoc)
            // stays silent here.
            return;
        }
        WorldFingerprintIO.ParseResult result = fingerprintIO.parse(new String(bytesRead.get(), StandardCharsets.UTF_8));
        if (result.warning() != null) {
            warningLogger.accept(result.warning());
        }
        fingerprintCache.replaceAll(result.entries());
    }

    /**
     * The size-threshold decision (FR6.3-FR6.5): a world at or under the
     * threshold always uses the whole archive; an over-threshold world is
     * always skipped entirely this checkpoint. Sync is strictly
     * all-or-nothing -- there is no partial/selective-fallback strategy.
     *
     * @param folderSizeBytes        the world folder's total on-disk size
     * @param maxWorldArchiveSizeMb  the configured threshold, in megabytes
     * @return the strategy to use
     */
    public static SyncStrategy decideStrategy(long folderSizeBytes, int maxWorldArchiveSizeMb) {
        long maxBytes = maxWorldArchiveSizeMb * 1024L * 1024L;
        return folderSizeBytes <= maxBytes ? SyncStrategy.WHOLE_ARCHIVE : SyncStrategy.SKIPPED;
    }

    /**
     * Computes a world folder's total on-disk size (FR6.3).
     *
     * @param worldFolder the world's on-disk save folder
     * @return the total size, in bytes, of every regular file under
     *         {@code worldFolder}
     * @throws IOException if the folder cannot be walked
     */
    public static long computeFolderSizeBytes(Path worldFolder) throws IOException {
        try (Stream<Path> stream = Files.walk(worldFolder)) {
            long total = 0L;
            for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                total += Files.size(path);
            }
            return total;
        }
    }

    /**
     * @param worldSlug the world's save-folder name
     * @return the flat, lowercase Cloud file name this world's archive is
     *         stored under
     */
    public static String archiveFileName(String worldSlug) {
        return "lazuli-world-" + worldSlug + ".zip";
    }

    /**
     * @param worldSlug the world's save-folder name
     * @return the flat, lowercase Cloud file name this world's per-world
     *         metadata file (cloud-world-metadata-file spec) is stored under
     */
    public static String metadataFileName(String worldSlug) {
        return "lazuli-world-meta-" + worldSlug + ".json";
    }

    /**
     * Builds and uploads the new per-world Cloud metadata file (Requirement
     * 1-3): computes {@code contentSignature} (a whole-folder SHA-256
     * content hash, unconditionally -- see this method's own Javadoc on
     * {@link #syncWorldNow(String, Path, String, WorldConflictResolutionHook.LevelDatBatch)}),
     * Base64-encodes {@code icon.png} if present (omitting the field
     * entirely, never erroring, if absent), and writes the resulting
     * {@link WorldCloudMetadata} via {@link #cloudFileStore}. Best-effort:
     * any failure is logged via {@link #warningLogger} and otherwise
     * swallowed, since a failed metadata upload must never block or fail the
     * archive upload it accompanies (Compatibility: a missing metadata file
     * degrades gracefully for every consumer).
     */
    private void buildAndUploadMetadata(String worldSlug, Path worldFolder, String displayName,
            WorldConflictResolutionHook.LevelDatBatch levelDatBatch, long syncedAtTimestamp) {
        try {
            String contentSignature = computeContentSignature(worldFolder);
            String iconBase64 = readIconBase64OrNull(worldFolder);
            // gameMode/hardcore/lastPlayedMillis are now sourced from the
            // real level.dat read threaded through levelDatBatch when the
            // caller has one (currently only onWorldUnload's Supplier<LevelDatBatch>
            // overload) -- falling back to the documented "unavailable"
            // sentinels ("Unknown"/false/this sync's own timestamp as a
            // proxy) when the batch is LevelDatBatch.unreadable() or its new
            // fields are otherwise absent, exactly as before for every other
            // checkpoint that still passes the sentinel batch.
            String gameMode = levelDatBatch.gameMode() != null ? levelDatBatch.gameMode() : "Unknown";
            boolean hardcore = levelDatBatch.hardcore();
            long lastPlayedMillis = levelDatBatch.lastPlayedMillis() >= 0
                    ? levelDatBatch.lastPlayedMillis()
                    : syncedAtTimestamp;
            WorldCloudMetadata metadata = new WorldCloudMetadata(
                    WorldCloudMetadataIO.CURRENT_SCHEMA_VERSION,
                    worldSlug,
                    displayName,
                    lastPlayedMillis,
                    levelDatBatch.minecraftVersion(),
                    levelDatBatch.seed(),
                    gameMode,
                    levelDatBatch.difficulty(),
                    hardcore,
                    contentSignature,
                    syncedAtTimestamp,
                    iconBase64);
            cloudFileStore.write(metadataFileName(worldSlug), metadataIO.serialize(metadata).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            warningLogger.accept("Failed to build/upload Cloud metadata file for world \"" + worldSlug + "\": " + e);
        }
    }

    /**
     * Computes a SHA-256 content-identity signal over the whole world
     * folder, reusing the same {@code Files.walk} traversal shape
     * {@link #computeFolderSizeBytes(Path)} already performs (Architecture:
     * "no new full-tree walk is introduced beyond one more accumulator") --
     * hashes the concatenation of each regular file's own root-relative path
     * (so a rename is detected, not just byte content) plus its bytes.
     * Computed unconditionally, for every world, on every sync checkpoint,
     * regardless of world size or {@link SyncStrategy} (see this class's
     * own {@code syncWorldNow} Javadoc).
     *
     * @param worldFolder the world's on-disk save folder
     * @return the hex-encoded SHA-256 digest
     * @throws IOException if the folder cannot be walked or a file cannot be read
     */
    static String computeContentSignature(Path worldFolder) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every conforming JVM (JLS-mandated).
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(worldFolder)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));
        for (Path file : files) {
            String relativePath = worldFolder.relativize(file).toString().replace('\\', '/');
            digest.update(relativePath.getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(file));
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * @param worldFolder the world's on-disk save folder
     * @return the Base64-encoded bytes of {@code worldFolder/icon.png}, or
     *         {@code null} if the world has no custom icon (tolerated, never
     *         an error -- not every world has one)
     */
    private static String readIconBase64OrNull(Path worldFolder) {
        Path iconPath = worldFolder.resolve("icon.png");
        if (!Files.isRegularFile(iconPath)) {
            return null;
        }
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(iconPath));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Read accessor for {@link WorldConflictResolutionHook#detailFor}/
     * {@code CloudOnlyWorldsFacade} (Requirement 4/5): synchronous, uncached
     * per-call {@link CloudFileStore#read} + parse, mirroring
     * {@link #pullFingerprints()}'s own read+parse pattern but for a single
     * per-world file rather than the all-worlds fingerprint list.
     *
     * @param worldSlug the world's save-folder name
     * @return the world's Cloud metadata, or {@link Optional#empty()} if no
     *         metadata file exists yet for this world (Compatibility: an old
     *         world synced before this feature shipped, or a metadata upload
     *         that failed independently of the archive upload) -- never throws
     */
    public Optional<WorldCloudMetadata> cloudMetadataFor(String worldSlug) {
        String cloudWorldId = resolveForRead(worldSlug);
        return cloudFileStore.read(metadataFileName(cloudWorldId)).flatMap(bytes -> {
            WorldCloudMetadataIO.ParseResult result = metadataIO.parse(new String(bytes, StandardCharsets.UTF_8));
            if (result.warning() != null) {
                warningLogger.accept(result.warning());
            }
            return Optional.ofNullable(result.metadata());
        });
    }

    /**
     * Deletes this world's per-world Cloud metadata file (Requirement 10),
     * called only from {@link #handleSyncDisabled(String, String)} alongside
     * the existing archive delete, so un-syncing a world leaves no orphaned
     * metadata file on Cloud.
     */
    private boolean deleteCloudMetadata(String worldSlug) {
        return cloudFileStore.delete(metadataFileName(worldSlug));
    }

    /**
     * The actual size-check/archive-build/fingerprint/quota orchestration,
     * package-private so tests can invoke it synchronously (bypassing the
     * {@link CloudSyncWorker} background-thread hop {@link #onWorldUnload}
     * uses in production) rather than racing a real background thread.
     */
    void syncWorldNow(String worldSlug, Path worldFolder, String displayName) {
        syncWorldNow(worldSlug, worldFolder, displayName, WorldConflictResolutionHook.LevelDatBatch.unreadable());
    }

    /**
     * Overload of {@link #syncWorldNow(String, Path, String)} that also
     * builds/uploads the new per-world Cloud metadata file (cloud-world-metadata-file
     * spec Requirement 3), sourcing its {@code level.dat}-derived fields from
     * {@code levelDatBatch}. The metadata upload happens independently of
     * whether the archive itself is {@code WHOLE_ARCHIVE} or {@code SKIPPED}
     * -- including {@code contentSignature}, computed unconditionally
     * (regardless of world size or archive strategy) per this feature's
     * resolved decision that the metadata file's every field, not only its
     * existence, is meant to upload even for an over-threshold {@code SKIPPED}
     * world.
     */
    void syncWorldNow(String worldSlug, Path worldFolder, String displayName,
            WorldConflictResolutionHook.LevelDatBatch levelDatBatch) {
        // cloud-sync-uuid-identity FR2.1/FR3.1: resolved once, at the top of
        // this method -- the one real Cloud-key write checkpoint every
        // public entry point (onWorldUnload/onWorldSaved/
        // checkAndUploadStaleWorldsAtStartup/handleSyncReenabled/
        // resolveKeepLocal) funnels through. Safe here: every caller of this
        // method already runs on CloudSyncWorker's background thread
        // (FR2.5), never the client tick thread. worldSlug (the raw, current
        // local folder name) is kept for every statusTracker/preferenceService
        // call below -- those stay keyed by "whatever folder name the
        // platform layer already uses" (FR6.1/FR3.4), not the Cloud key.
        String cloudWorldId = migrationService.resolveCloudWorldId(worldSlug).toString();
        try {
            long sizeBytes = computeFolderSizeBytes(worldFolder);
            SyncStrategy strategy = decideStrategy(sizeBytes, maxWorldArchiveSizeMb);

            long syncedAtTimestamp = System.currentTimeMillis();
            buildAndUploadMetadata(cloudWorldId, worldFolder, displayName, levelDatBatch, syncedAtTimestamp);

            if (strategy == SyncStrategy.SKIPPED) {
                playerNotifier.accept("World \"" + displayName + "\" (" + formatMb(sizeBytes) + " MB) exceeds the "
                        + maxWorldArchiveSizeMb + " MB Cloud sync threshold; not synced this session.");
                statusTracker.markSkippedTooLarge(worldSlug);
                statusTracker.markUploadFinished(worldSlug);
                return;
            }

            byte[] archiveBytes = buildWholeArchive(worldFolder);

            checkFingerprintForConflict(cloudWorldId, displayName);
            ensureQuota(archiveBytes.length, cloudWorldId);

            String archiveFileName = archiveFileName(cloudWorldId);
            playerNotifier.accept("Uploading world \"" + displayName + "\" (" + archiveBytes.length + " bytes) to Steam Cloud.");
            worker.enqueueTickThreadWork(() -> {
                // DEV-ONLY: see DebugSyncFaultInjector's javadoc for how to
                // flip this on to manually test the conflict-resolution flow.
                boolean written = !DebugSyncFaultInjector.shouldForceUploadFailure()
                        && archiveStore.streamWrite(archiveFileName, archiveBytes);
                try {
                    if (written) {
                        updateFingerprint(cloudWorldId, displayName);
                        statusTracker.markSynced(worldSlug);
                        playerNotifier.accept("Uploaded world \"" + displayName + "\" to Steam Cloud.");
                    } else {
                        String message = "Failed to sync world \"" + displayName + "\" (" + archiveBytes.length
                                + " bytes) to Steam Cloud; see the preceding Steam Cloud log line for the specific cause.";
                        warningLogger.accept(message);
                        statusTracker.markError(worldSlug, message);
                    }
                } finally {
                    statusTracker.markUploadFinished(worldSlug);
                }
            });
        } catch (IOException e) {
            warningLogger.accept("Failed to build Cloud archive for world \"" + displayName + "\": " + e);
            statusTracker.markError(worldSlug, e.getMessage());
            statusTracker.markUploadFinished(worldSlug);
        }
    }

    private byte[] buildWholeArchive(Path worldFolder) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            try (Stream<Path> stream = Files.walk(worldFolder)) {
                for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    addZipEntry(zip, worldFolder, path);
                }
            }
        }
        return buffer.toByteArray();
    }

    private void addZipEntry(ZipOutputStream zip, Path root, Path file) throws IOException {
        String entryName = root.relativize(file).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private void checkFingerprintForConflict(String worldSlug, String displayName) {
        readLocalFingerprintCache().stream()
                .filter(fingerprint -> fingerprint.worldSlug().equals(worldSlug))
                .findFirst()
                .ifPresent(existing -> {
                    if (!existing.deviceLabel().equals(deviceLabel)) {
                        playerNotifier.accept("World \"" + displayName + "\" was last synced from \""
                                + existing.deviceLabel() + "\" at " + Instant.ofEpochMilli(existing.syncedAtTimestamp())
                                + "; continuing will overwrite that copy.");
                    }
                });
    }

    private void ensureQuota(int neededBytes, String worldSlugBeingWritten) {
        long[] total = new long[1];
        long[] available = new long[1];
        if (!archiveStore.getQuota(total, available)) {
            warningLogger.accept("Steam Cloud quota check failed for world \"" + worldSlugBeingWritten
                    + "\" (Steam getQuota() call did not succeed); proceeding without a quota pre-check.");
            return;
        }
        if (available[0] >= neededBytes) {
            return;
        }

        List<WorldFingerprint> candidates = new ArrayList<>(readLocalFingerprintCache());
        candidates.removeIf(fingerprint -> fingerprint.worldSlug().equals(worldSlugBeingWritten));
        candidates.sort(Comparator.comparingLong(WorldFingerprint::syncedAtTimestamp));

        int evicted = 0;
        for (WorldFingerprint candidate : candidates) {
            if (available[0] >= neededBytes) {
                break;
            }
            if (archiveStore.forget(archiveFileName(candidate.worldSlug()))) {
                evicted++;
                playerNotifier.accept("World \"" + candidate.displayName()
                        + "\" is no longer Cloud-backed (still fully playable locally) -- "
                        + "Cloud quota was needed for another world.");
                archiveStore.getQuota(total, available);
            }
        }

        if (available[0] < neededBytes) {
            warningLogger.accept("Cloud quota still insufficient for world \"" + worldSlugBeingWritten
                    + "\" after evicting " + evicted + " older world(s): need " + neededBytes + " bytes, have "
                    + available[0] + " of " + total[0] + " bytes total. The write that follows is expected to fail.");
        }
    }

    private void updateFingerprint(String worldSlug, String displayName) {
        long syncedAtTimestamp = System.currentTimeMillis();
        List<WorldFingerprint> fingerprints = new ArrayList<>(readLocalFingerprintCache());
        fingerprints.removeIf(fingerprint -> fingerprint.worldSlug().equals(worldSlug));
        fingerprints.add(new WorldFingerprint(worldSlug, displayName, deviceLabel, syncedAtTimestamp));
        fingerprintCache.replaceAll(fingerprints);
        cloudFileStore.write(FINGERPRINT_CLOUD_FILE_NAME, fingerprintIO.serialize(fingerprints).getBytes(StandardCharsets.UTF_8));

        // F20e: immediately after this device's own successful upload, the
        // global fingerprint and this device's own "last known common
        // ancestor" are, by construction, the same value -- record it
        // locally only, never pushed to cloudFileStore.
        writeAncestorEntry(worldSlug, deviceLabel, syncedAtTimestamp);
    }

    private List<WorldSyncAncestor> readLocalAncestorCache() {
        try {
            if (Files.notExists(ancestorCachePath)) {
                return List.of();
            }
            WorldSyncAncestorIO.ParseResult result = ancestorIO.parse(Files.readString(ancestorCachePath, StandardCharsets.UTF_8));
            if (result.warning() != null) {
                warningLogger.accept(result.warning());
            }
            return result.entries();
        } catch (IOException e) {
            warningLogger.accept("Failed to read " + ancestorCachePath + ": " + e);
            return List.of();
        }
    }

    /**
     * Writes/replaces this device's own ancestor entry for {@code worldSlug}.
     * Local-only -- never written to {@link #cloudFileStore}.
     */
    private void writeAncestorEntry(String worldSlug, String recordedDeviceLabel, long syncedAtTimestamp) {
        List<WorldSyncAncestor> ancestors = new ArrayList<>(readLocalAncestorCache());
        ancestors.removeIf(entry -> entry.worldSlug().equals(worldSlug));
        ancestors.add(new WorldSyncAncestor(worldSlug, recordedDeviceLabel, syncedAtTimestamp));
        try {
            Path parent = ancestorCachePath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(ancestorCachePath, ancestorIO.serialize(ancestors), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warningLogger.accept("Failed to write " + ancestorCachePath + ": " + e);
        }
    }

    /**
     * @return this process's current, RAM-only snapshot of Cloud's
     *         fingerprint file (see {@link #fingerprintCache}'s Javadoc) --
     *         never read from or written to any local file.
     */
    private List<WorldFingerprint> readLocalFingerprintCache() {
        return fingerprintCache.entries();
    }

    private static String formatMb(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }
}
