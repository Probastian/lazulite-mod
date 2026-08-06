package de.lazuli.features.steamcloudsync.services;

import de.lazuli.features.steamcloudsync.api.WorldCloudMetadata;
import de.lazuli.features.steamcloudsync.api.WorldFingerprint;
import de.lazuli.features.steamcloudsync.config.WorldCloudMetadataIO;
import de.lazuli.features.steamcloudsync.config.WorldCloudMigrationIO;
import de.lazuli.features.steamcloudsync.config.WorldFingerprintIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * cloud-sync-uuid-identity spec: the migration orchestrator plus thin
 * breadcrumb store driving FR2.1 (Phase A -- Cloud-side identity resolution)
 * and FR2.2 (Phase B -- physical folder rename). See that spec's
 * Architecture -- "Migration &amp; rename sequencing" section for the full
 * state machine this class implements.
 *
 * <p>Deviation from the spec's literal Public API pseudocode: this
 * constructor additionally takes a {@link WorldFingerprintCache}, needed so
 * Phase A's fingerprint-entry rewrite (FR2.1 step 4) can keep this process's
 * RAM-only fingerprint cache ({@link WorldSaveSyncService}'s own dependency)
 * in lockstep with what gets written to Cloud -- the spec's own pseudocode
 * omitted this dependency, but Phase A cannot correctly rewrite "this world's
 * {@code WorldFingerprint} list entry" without it (the alternative, writing
 * only to Cloud and letting the next {@code pullFingerprints()} call
 * eventually reconcile, would leave the in-memory cache holding a stale,
 * duplicate old-keyed entry until that next pull).
 */
public class WorldCloudMigrationService {

    private static final String FINGERPRINT_CLOUD_FILE_NAME = "lazuli-world-fingerprints.json";

    /** FR2.2's Risk #3 signal: one completed physical rename, surfaced to platform code so cached row state can be invalidated. */
    public record RenameEvent(String oldFolderName, String newFolderName) {
    }

    private static final class BreadcrumbState {
        final UUID cloudWorldId;
        volatile boolean cloudMigrated;
        volatile boolean renamed;

        BreadcrumbState(UUID cloudWorldId, boolean cloudMigrated, boolean renamed) {
            this.cloudWorldId = cloudWorldId;
            this.cloudMigrated = cloudMigrated;
            this.renamed = renamed;
        }
    }

    private final Path breadcrumbFilePath;
    private final Path savesDirectory;
    private final WorldArchiveCloudStore archiveStore;
    private final CloudFileStore cloudFileStore;
    private final WorldFingerprintCache fingerprintCache;
    private final WorldSyncPreferenceService preferenceService;
    private final Consumer<String> warningLogger;
    private final Consumer<String> infoLogger;

    private final WorldCloudMigrationIO io = new WorldCloudMigrationIO();
    private final WorldFingerprintIO fingerprintIO = new WorldFingerprintIO();
    private final WorldCloudMetadataIO metadataIO = new WorldCloudMetadataIO();

    /** Keyed by {@code oldFolderName}; synchronized on {@code this} for every access. */
    private final Map<String, BreadcrumbState> breadcrumbs = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<RenameEvent> recentRenames = new ConcurrentLinkedQueue<>();

    public WorldCloudMigrationService(
            Path breadcrumbFilePath,
            Path savesDirectory,
            WorldArchiveCloudStore archiveStore,
            CloudFileStore cloudFileStore,
            WorldFingerprintCache fingerprintCache,
            WorldSyncPreferenceService preferenceService,
            Consumer<String> warningLogger,
            Consumer<String> infoLogger) {
        this.breadcrumbFilePath = Objects.requireNonNull(breadcrumbFilePath, "breadcrumbFilePath");
        this.savesDirectory = Objects.requireNonNull(savesDirectory, "savesDirectory");
        this.archiveStore = Objects.requireNonNull(archiveStore, "archiveStore");
        this.cloudFileStore = Objects.requireNonNull(cloudFileStore, "cloudFileStore");
        this.fingerprintCache = Objects.requireNonNull(fingerprintCache, "fingerprintCache");
        this.preferenceService = Objects.requireNonNull(preferenceService, "preferenceService");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.infoLogger = Objects.requireNonNull(infoLogger, "infoLogger");
    }

    /** Reads {@code world-cloud-migration.json} into memory. Call once, before this service is used. */
    public synchronized void load() {
        WorldCloudMigrationIO.ParseResult result = io.load(breadcrumbFilePath);
        if (result.warning() != null) {
            warningLogger.accept(result.warning());
        }
        breadcrumbs.clear();
        for (WorldCloudMigrationIO.Entry entry : result.entries()) {
            try {
                breadcrumbs.put(entry.oldFolderName(),
                        new BreadcrumbState(UUID.fromString(entry.cloudWorldId()), entry.cloudMigrated(), entry.renamed()));
            } catch (IllegalArgumentException e) {
                warningLogger.accept("Ignoring malformed cloudWorldId in world-cloud-migration.json for \""
                        + entry.oldFolderName() + "\": " + entry.cloudWorldId());
            }
        }
    }

    /**
     * FR1.2/FR1.3: local-only, synchronous, zero I/O beyond the folder-name
     * parse itself (fast path), or an in-memory breadcrumb lookup.
     *
     * @param currentFolderName the local save folder's current name
     * @return the folder's {@code cloudWorldId} if already resolvable with no
     *         Cloud I/O; empty if not yet migrated/no migration started
     */
    public Optional<UUID> existingCloudWorldId(String currentFolderName) {
        Optional<UUID> parsed = tryParseUuid(currentFolderName);
        if (parsed.isPresent()) {
            return parsed;
        }
        synchronized (this) {
            BreadcrumbState state = breadcrumbs.get(currentFolderName);
            return state != null ? Optional.of(state.cloudWorldId) : Optional.empty();
        }
    }

    private static Optional<UUID> tryParseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * FR2.1 (Phase A): resolves (minting + migrating Cloud data as needed)
     * {@code currentFolderName}'s {@code cloudWorldId}. Must be called off
     * the client tick thread. Never touches the local folder.
     *
     * @param currentFolderName the local save folder's current name
     * @return the resolved {@code cloudWorldId}
     */
    public UUID resolveCloudWorldId(String currentFolderName) {
        Optional<UUID> alreadyUuid = tryParseUuid(currentFolderName);
        if (alreadyUuid.isPresent()) {
            return alreadyUuid.get();
        }

        BreadcrumbState state;
        synchronized (this) {
            state = breadcrumbs.get(currentFolderName);
            if (state == null) {
                state = new BreadcrumbState(UUID.randomUUID(), false, false);
                breadcrumbs.put(currentFolderName, state);
                persistLocked();
            }
        }

        if (!state.cloudMigrated) {
            runPhaseA(currentFolderName, state);
        }
        return state.cloudWorldId;
    }

    /** FR2.1 steps 4-7: the actual Cloud-side migration, retried from scratch (idempotently) if any step fails. */
    private void runPhaseA(String oldFolderName, BreadcrumbState state) {
        String oldSlug = oldFolderName;
        String newSlug = state.cloudWorldId.toString();
        try {
            String oldArchiveName = WorldSaveSyncService.archiveFileName(oldSlug);
            int oldArchiveSize = archiveStore.fileSize(oldArchiveName);
            if (oldArchiveSize > 0) {
                if (!migrateOldKeyedData(oldSlug, newSlug)) {
                    warningLogger.accept("Phase A migration failed for world \"" + oldFolderName
                            + "\" (cloudWorldId " + newSlug + "); will retry at the next sync checkpoint.");
                    return;
                }
            }
            synchronized (this) {
                state.cloudMigrated = true;
                persistLocked();
            }
            infoLogger.accept("Migrated world \"" + oldFolderName + "\" to cloudWorldId " + newSlug + " (Phase A complete).");
        } catch (RuntimeException e) {
            warningLogger.accept("Phase A migration threw for world \"" + oldFolderName + "\": " + e);
        }
    }

    /**
     * FR2.1 step 4: copies the old-keyed archive (already known present) and
     * optional metadata file to the new key, rewrites the fingerprint entry,
     * then deletes the old-keyed archive/metadata only once every write has
     * succeeded.
     *
     * @return {@code true} if the whole migration succeeded
     */
    private boolean migrateOldKeyedData(String oldSlug, String newSlug) {
        String oldArchiveName = WorldSaveSyncService.archiveFileName(oldSlug);
        String newArchiveName = WorldSaveSyncService.archiveFileName(newSlug);
        String oldMetadataName = WorldSaveSyncService.metadataFileName(oldSlug);
        String newMetadataName = WorldSaveSyncService.metadataFileName(newSlug);

        byte[] archiveBytes = readWholeArchive(oldArchiveName);
        if (archiveBytes == null) {
            warningLogger.accept("Phase A: could not read old-keyed archive \"" + oldArchiveName + "\"; aborting migration.");
            return false;
        }
        if (!archiveStore.streamWrite(newArchiveName, archiveBytes)) {
            warningLogger.accept("Phase A: failed to write migrated archive \"" + newArchiveName + "\"; aborting migration.");
            return false;
        }

        Optional<byte[]> metadataBytes = cloudFileStore.read(oldMetadataName);
        if (metadataBytes.isPresent()) {
            String rewritten = rewriteMetadataWorldSlug(metadataBytes.get(), newSlug);
            if (rewritten != null) {
                cloudFileStore.write(newMetadataName, rewritten.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (!rewriteFingerprintEntry(oldSlug, newSlug)) {
            warningLogger.accept("Phase A: failed to rewrite fingerprint entry for \"" + oldSlug + "\" -> \"" + newSlug + "\".");
            return false;
        }

        archiveStore.deleteWorldArchive(oldArchiveName);
        if (metadataBytes.isPresent()) {
            cloudFileStore.delete(oldMetadataName);
        }
        return true;
    }

    private byte[] readWholeArchive(String archiveFileName) {
        byte[][] result = new byte[1][];
        boolean[] failed = new boolean[1];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        archiveStore.beginAsyncRead(archiveFileName, new WorldArchiveCloudStore.AsyncReadListener() {
            @Override
            public void onChunk(byte[] chunk) {
                buffer.writeBytes(chunk);
            }

            @Override
            public void onComplete() {
                result[0] = buffer.toByteArray();
                latch.countDown();
            }

            @Override
            public void onFailed(String reason) {
                failed[0] = true;
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return failed[0] ? null : result[0];
    }

    private String rewriteMetadataWorldSlug(byte[] oldBytes, String newSlug) {
        try {
            WorldCloudMetadataIO.ParseResult parsed = metadataIO.parse(new String(oldBytes, StandardCharsets.UTF_8));
            if (parsed.warning() != null || parsed.metadata() == null) {
                return null;
            }
            WorldCloudMetadata old = parsed.metadata();
            WorldCloudMetadata rewritten = new WorldCloudMetadata(
                    old.schemaVersion(), newSlug, old.displayName(), old.lastPlayedMillis(), old.minecraftVersion(),
                    old.seed(), old.gameMode(), old.difficulty(), old.hardcore(), old.contentSignature(),
                    old.syncedAtTimestamp(), old.iconBase64());
            return metadataIO.serialize(rewritten);
        } catch (RuntimeException e) {
            warningLogger.accept("Phase A: failed to rewrite metadata worldSlug for \"" + newSlug + "\": " + e);
            return null;
        }
    }

    private boolean rewriteFingerprintEntry(String oldSlug, String newSlug) {
        try {
            List<WorldFingerprint> fingerprints = new ArrayList<>(fingerprintCache.entries());
            WorldFingerprint existing = fingerprints.stream()
                    .filter(fp -> fp.worldSlug().equals(oldSlug))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                // Nothing to rewrite (e.g. an old archive existed with no
                // matching fingerprint entry, an edge case but not fatal --
                // the archive/metadata copy above still succeeded).
                return true;
            }
            fingerprints.removeIf(fp -> fp.worldSlug().equals(oldSlug));
            fingerprints.add(new WorldFingerprint(newSlug, existing.displayName(), existing.deviceLabel(), existing.syncedAtTimestamp()));
            String serialized = fingerprintIO.serialize(fingerprints);
            if (!cloudFileStore.write(FINGERPRINT_CLOUD_FILE_NAME, serialized.getBytes(StandardCharsets.UTF_8))) {
                return false;
            }
            fingerprintCache.replaceAll(fingerprints);
            return true;
        } catch (RuntimeException e) {
            warningLogger.accept("Phase A: failed to rewrite fingerprint entry \"" + oldSlug + "\" -> \"" + newSlug + "\": " + e);
            return false;
        }
    }

    /**
     * FR2.2 (Phase B): attempts the physical rename for every breadcrumb
     * entry with {@code cloudMigrated=true, renamed=false}. Caller must only
     * invoke this from a checkpoint it can guarantee is at the main menu
     * (Risk #1/#3).
     */
    public void runPendingRenames() {
        List<Map.Entry<String, BreadcrumbState>> candidates;
        synchronized (this) {
            candidates = new ArrayList<>();
            for (Map.Entry<String, BreadcrumbState> entry : breadcrumbs.entrySet()) {
                if (entry.getValue().cloudMigrated && !entry.getValue().renamed) {
                    candidates.add(entry);
                }
            }
        }
        for (Map.Entry<String, BreadcrumbState> entry : candidates) {
            attemptRename(entry.getKey(), entry.getValue());
        }
    }

    private void attemptRename(String oldFolderName, BreadcrumbState state) {
        Path oldPath = savesDirectory.resolve(oldFolderName);
        Path newPath = savesDirectory.resolve(state.cloudWorldId.toString());
        try {
            if (Files.notExists(oldPath)) {
                warningLogger.accept("Phase B: local folder \"" + oldFolderName + "\" no longer exists; leaving its breadcrumb as-is.");
                return;
            }
            Files.move(oldPath, newPath);
            synchronized (this) {
                state.renamed = true;
                persistLocked();
            }
            preferenceService.renameKey(oldFolderName, state.cloudWorldId.toString());
            recentRenames.add(new RenameEvent(oldFolderName, state.cloudWorldId.toString()));
            infoLogger.accept("Renamed local world folder \"" + oldFolderName + "\" to \"" + state.cloudWorldId + "\" (Phase B complete).");
        } catch (IOException | RuntimeException e) {
            warningLogger.accept("Phase B: failed to rename \"" + oldFolderName + "\" to \"" + state.cloudWorldId
                    + "\" (will retry at the next main-menu checkpoint): " + e);
        }
    }

    /**
     * FR4.2: every {@code cloudWorldId} with {@code cloudMigrated=true} right
     * now (renamed or not) whose local save folder still actually exists on
     * disk.
     *
     * <p>The breadcrumb file accumulates one entry per world this install has
     * ever migrated and is never pruned when the corresponding local folder
     * is later deleted (e.g. the player deletes the world, or it was only
     * ever a stale/orphaned entry from earlier local testing). Without this
     * existence check, such a stale entry permanently excludes its
     * {@code cloudWorldId} from {@link CloudOnlyWorldsFacade}'s cloud-only
     * list on this install forever, even though the world has no local
     * presence at all anymore -- a false "known locally" signal.
     */
    public synchronized Set<UUID> knownLocalCloudWorldIds() {
        Set<UUID> result = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, BreadcrumbState> entry : breadcrumbs.entrySet()) {
            BreadcrumbState state = entry.getValue();
            if (!state.cloudMigrated) {
                continue;
            }
            String expectedFolderName = state.renamed ? state.cloudWorldId.toString() : entry.getKey();
            if (Files.exists(savesDirectory.resolve(expectedFolderName))) {
                result.add(state.cloudWorldId);
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Risk #3: drains and returns every {@link RenameEvent} recorded by
     * {@link #runPendingRenames()} since the last call -- polled by a
     * platform Version Adapter ({@code WorldsPanel}) so a renamed row's
     * cached state (expanded-row id, freshness/conflict caches) can be
     * invalidated the moment a rename actually happens, rather than only on
     * the next unrelated {@code reload()}.
     */
    public List<RenameEvent> drainRecentRenames() {
        List<RenameEvent> drained = new ArrayList<>();
        RenameEvent event;
        while ((event = recentRenames.poll()) != null) {
            drained.add(event);
        }
        return drained;
    }

    /** Must be called while holding {@code this}'s monitor. */
    private void persistLocked() {
        List<WorldCloudMigrationIO.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, BreadcrumbState> entry : breadcrumbs.entrySet()) {
            BreadcrumbState state = entry.getValue();
            entries.add(new WorldCloudMigrationIO.Entry(
                    entry.getKey(), state.cloudWorldId.toString(), state.cloudMigrated, state.renamed));
        }
        try {
            io.save(breadcrumbFilePath, entries);
        } catch (IOException e) {
            warningLogger.accept("Failed to write " + breadcrumbFilePath + ": " + e);
        }
    }
}
