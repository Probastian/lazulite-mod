package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.CloudOnlyWorldSummary;
import de.lazuli.api.cloudsync.CloudOnlyWorldsHook;
import de.lazuli.features.steamcloudsync.api.WorldCloudMetadata;
import de.lazuli.features.steamcloudsync.api.WorldFingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Thin {@link CloudOnlyWorldsHook} implementation composing the pure
 * {@link CloudOnlyWorldDetector} with this device's RAM-only, already-pulled
 * {@link WorldFingerprintCache} (FR6.6/FR6.8) -- the Cloud fingerprint file
 * itself is only ever pulled from Steam at the client-startup checkpoint
 * (FR0.3) and other refresh checkpoints, like every other Cloud file; this
 * facade just re-reads that already-in-memory snapshot each time it is
 * asked, which stays cheap and synchronous on the render/client thread
 * (FR6.8). Deliberately never reads from or writes to any local file: see
 * {@link WorldFingerprintCache}'s Javadoc for why this state must not
 * survive a process restart via disk persistence.
 *
 * <p>Usage example (from a platform Version Adapter holding this facade
 * through its {@link CloudOnlyWorldsHook} contract):
 * <pre>{@code
 * CloudOnlyWorldsHook hook = new CloudOnlyWorldsFacade(fingerprintCache, worldSaveSyncService, migrationService, infoLogger);
 * List<CloudOnlyWorldSummary> cloudOnly = hook.listCloudOnlyWorlds(localSaveFolderNames);
 * }</pre>
 */
public final class CloudOnlyWorldsFacade implements CloudOnlyWorldsHook {

    private final CloudOnlyWorldDetector detector = new CloudOnlyWorldDetector();
    private final WorldFingerprintCache fingerprintCache;
    private final WorldSaveSyncService worldSaveSyncService;
    private final WorldCloudMigrationService migrationService;
    private final Consumer<String> infoLogger;

    /**
     * @param fingerprintCache     this process's RAM-only snapshot of Cloud's
     *                             fingerprint file (kept up to date by
     *                             {@code WorldSaveSyncService#pullFingerprints()}
     *                             at the client-startup checkpoint and other
     *                             refresh checkpoints)
     * @param worldSaveSyncService cloud-world-metadata-file spec Requirement
     *                             5's new coupling: used only for its
     *                             synchronous, per-call
     *                             {@link WorldSaveSyncService#cloudMetadataFor(String)}
     *                             read, to attach the richer per-world
     *                             fields to each detected cloud-only world
     * @param infoLogger           receives a human-readable diagnostic message
     *                             (this feature's usual info-level logging
     *                             convention -- see e.g.
     *                             {@link WorldCloudMigrationService}) each time
     *                             {@link #listCloudOnlyWorlds(List)} actually
     *                             recomputes the cloud-only list: the raw
     *                             Steam Cloud fingerprint listing, every
     *                             exclusion decision and its reason, and the
     *                             final result -- this only fires on the
     *                             refresh checkpoints that call this method
     *                             (e.g. {@code WorldsPanel#refreshCloudOnlyWorlds()}),
     *                             never on a per-render-frame basis
     */
    public CloudOnlyWorldsFacade(WorldFingerprintCache fingerprintCache, WorldSaveSyncService worldSaveSyncService,
            WorldCloudMigrationService migrationService, Consumer<String> infoLogger) {
        this.fingerprintCache = Objects.requireNonNull(fingerprintCache, "fingerprintCache");
        this.worldSaveSyncService = Objects.requireNonNull(worldSaveSyncService, "worldSaveSyncService");
        this.migrationService = Objects.requireNonNull(migrationService, "migrationService");
        this.infoLogger = Objects.requireNonNull(infoLogger, "infoLogger");
    }

    @Override
    public List<CloudOnlyWorldSummary> listCloudOnlyWorlds(List<String> localWorldFolderNames) {
        List<WorldFingerprint> fingerprints = fingerprintCache.entries();
        List<String> rawCloudWorldSlugs = new ArrayList<>(fingerprints.size());
        for (WorldFingerprint fingerprint : fingerprints) {
            rawCloudWorldSlugs.add(fingerprint.worldSlug());
        }
        infoLogger.accept("[CloudOnlyWorlds] raw cloud listing (" + rawCloudWorldSlugs.size() + "): " + rawCloudWorldSlugs);

        java.util.Set<String> pendingRenameCloudWorldIds = new java.util.LinkedHashSet<>();
        migrationService.knownLocalCloudWorldIds().forEach(id -> pendingRenameCloudWorldIds.add(id.toString()));
        List<CloudOnlyWorldSummary> baseSummaries = detector.detect(localWorldFolderNames, fingerprints, pendingRenameCloudWorldIds);

        java.util.Set<String> localFolderSet = new java.util.HashSet<>(localWorldFolderNames);
        List<String> excludedSummary = new ArrayList<>();
        for (String worldSlug : rawCloudWorldSlugs) {
            if (localFolderSet.contains(worldSlug)) {
                excludedSummary.add(worldSlug + "(reason=localFolderExists)");
            } else if (pendingRenameCloudWorldIds.contains(worldSlug)) {
                excludedSummary.add(worldSlug + "(reason=knownLocalCloudWorldIds/pendingRename)");
            }
        }
        infoLogger.accept("[CloudOnlyWorlds] excluded " + excludedSummary.size() + " entr" + (excludedSummary.size() == 1 ? "y" : "ies")
                + " during filtering: " + excludedSummary);

        List<CloudOnlyWorldSummary> enriched = new ArrayList<>(baseSummaries.size());
        for (CloudOnlyWorldSummary summary : baseSummaries) {
            enriched.add(attachMetadata(summary));
        }
        List<CloudOnlyWorldSummary> result = List.copyOf(enriched);

        List<String> resultSlugs = new ArrayList<>(result.size());
        for (CloudOnlyWorldSummary summary : result) {
            resultSlugs.add(summary.worldSlug());
        }
        infoLogger.accept("[CloudOnlyWorlds] final cloud-only list (" + resultSlugs.size() + "): " + resultSlugs);

        return result;
    }

    /**
     * Attaches the richer, {@link WorldCloudMetadata}-sourced fields to
     * {@code summary} when a metadata file exists for its world; returns
     * {@code summary} unchanged (at its documented "unavailable" sentinels)
     * when it does not (Compatibility -- an old world synced before this
     * feature shipped, or a metadata upload that failed independently of the
     * archive upload).
     */
    private CloudOnlyWorldSummary attachMetadata(CloudOnlyWorldSummary summary) {
        Optional<WorldCloudMetadata> metadata = worldSaveSyncService.cloudMetadataFor(summary.worldSlug());
        if (metadata.isEmpty()) {
            return summary;
        }
        WorldCloudMetadata m = metadata.get();
        return new CloudOnlyWorldSummary(
                summary.worldSlug(), summary.displayName(), summary.deviceLabel(), summary.syncedAtTimestamp(),
                m.lastPlayedMillis(), m.minecraftVersion(), m.seed(), m.gameMode(), m.difficulty(), m.hardcore(),
                m.iconBase64());
    }
}
