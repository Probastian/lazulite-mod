package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.CloudOnlyWorldsHook;
import de.lazuli.api.cloudsync.CloudSyncable;
import de.lazuli.api.steamworks.SteamAvailability;
import de.lazuli.features.steamcloudsync.api.LastPlayedPointer;
import de.lazuli.features.steamcloudsync.api.SteamCloudSyncConfig;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The composition point for every Cloud checkpoint this feature defines
 * (FR0.3): constructs the {@link CloudFileStore}/{@link WorldArchiveCloudStore}
 * pair (real or no-op, gated on {@link SteamAvailability#isSteamAvailable()}
 * at construction time), constructs all six group services with those stores
 * injected, owns the {@link CloudSyncWorker}, and applies the FR0.4
 * reconciliation rule at the client-startup checkpoint plus a final push at
 * the client-shutdown checkpoint.
 *
 * <p>{@link CloudSyncable} reconciliation applies the same FR0.4
 * "closest we can get to last-write-wins" rule Groups 3-5 already apply to
 * their own plain local files -- via {@link CloudSyncableReconciler}, which
 * compares the Cloud copy's timestamp against
 * {@link CloudSyncable#localLastModifiedMillis()} instead of a
 * {@link java.nio.file.Path}'s last-modified time, so the contract never
 * needs to leak a {@code Path} or any other implementation detail of the
 * Feature it bridges. The client-shutdown checkpoint then pushes whatever
 * this session accumulated (FR1.3/FR1.4), same as Groups 3-5's own shutdown
 * checkpoint.
 *
 * <p>Every registered {@link CloudSyncable} today is a Group 1 ("Settings")
 * adapter (only {@code hello-world-main-menu} exists so far); this
 * coordinator therefore gates the whole list uniformly on
 * {@code config.syncSettings()}. FR2.2 states Group 2 needs "zero additional
 * design ... beyond Group 1's mechanism" -- distinguishing a future Group-2
 * {@link CloudSyncable} from a Group-1 one for independent toggle gating is
 * left for whenever a second, real registrant actually exists.
 *
 * <p>Usage example (from a platform composition root):
 * <pre>{@code
 * CloudSyncCoordinator coordinator = new CloudSyncCoordinator(
 *         steamworksService, config, cloudSyncables, featureConfigDir, savesDirectory,
 *         LazuliMod.LOGGER::warn, LazuliMod.LOGGER::info, pointer -> LazuliMod.LOGGER.info("{}", pointer));
 * coordinator.reconcileAtStartup();
 * ClientLifecycleEvents.CLIENT_STOPPING.register(client -> coordinator.syncOnShutdown());
 * ClientTickEvents.END_CLIENT_TICK.register(client -> coordinator.cloudSyncWorker().pumpTickWork());
 * }</pre>
 */
public final class CloudSyncCoordinator {

    private final CloudFileStore cloudFileStore;
    private final WorldArchiveCloudStore archiveStore;
    private final CloudSyncWorker worker;
    private final List<CloudSyncable> cloudSyncables;
    private final SteamCloudSyncConfig config;
    private final Consumer<String> warningLogger;

    private final BookmarkedServersService bookmarkedServersService;
    private final NotesService notesService;
    private final LastPlayedPointerService lastPlayedPointerService;
    private final WorldSyncPreferenceService worldSyncPreferenceService;
    private final WorldSyncStatusTracker worldSyncStatusTracker;
    private final WorldSaveSyncService worldSaveSyncService;
    private final WorldRestoreService worldRestoreService;
    private final CloudOnlyWorldsHook cloudOnlyWorldsFacade;

    /**
     * @param steamAvailability      whether Steam is available for this
     *                               process; determines real vs. no-op Cloud
     *                               stores
     * @param config                 this feature's own loaded settings
     * @param cloudSyncables         every opted-in {@link CloudSyncable},
     *                               aggregated by the platform composition
     *                               root (FR1.2)
     * @param featureConfigDir       this feature's own local config
     *                               directory (e.g.
     *                               {@code config/steam-cloud-sync/})
     * @param savesDirectory         this device's local worlds/saves
     *                               directory (Group 6)
     * @param warningLogger          receives a human-readable message for any
     *                               internal failure; never invoked with a
     *                               thrown exception
     * @param playerNotifier         receives a human-readable, player-visible
     *                               message for Group 6's FR6.4/FR6.6/FR6.7
     *                               notifications
     * @param continuePointerNotifier receives a newer Cloud
     *                               {@link LastPlayedPointer} found at
     *                               startup (FR4.3)
     */
    public CloudSyncCoordinator(
            SteamAvailability steamAvailability,
            SteamCloudSyncConfig config,
            List<CloudSyncable> cloudSyncables,
            Path featureConfigDir,
            Path savesDirectory,
            Consumer<String> warningLogger,
            Consumer<String> playerNotifier,
            Consumer<LastPlayedPointer> continuePointerNotifier) {
        Objects.requireNonNull(steamAvailability, "steamAvailability");
        this.config = Objects.requireNonNull(config, "config");
        this.cloudSyncables = List.copyOf(Objects.requireNonNull(cloudSyncables, "cloudSyncables"));
        Objects.requireNonNull(featureConfigDir, "featureConfigDir");
        Objects.requireNonNull(savesDirectory, "savesDirectory");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        Objects.requireNonNull(playerNotifier, "playerNotifier");
        Objects.requireNonNull(continuePointerNotifier, "continuePointerNotifier");

        boolean available = steamAvailability.isSteamAvailable();
        this.cloudFileStore = available ? new SteamRemoteStorageCloudFileStore(warningLogger) : new NoopCloudFileStore();
        this.archiveStore = available ? new SteamRemoteStorageWorldArchiveStore(warningLogger) : new NoopWorldArchiveCloudStore();
        this.worker = new CloudSyncWorker(warningLogger);

        boolean masterEnabled = config.enabled();

        this.bookmarkedServersService = new BookmarkedServersService(
                cloudFileStore, featureConfigDir.resolve("bookmarked-servers.json"),
                masterEnabled && config.syncBookmarkedServers(), warningLogger);
        this.notesService = new NotesService(
                cloudFileStore, featureConfigDir.resolve("notes.json"),
                masterEnabled && config.syncNotes(), warningLogger);
        this.lastPlayedPointerService = new LastPlayedPointerService(
                cloudFileStore, featureConfigDir.resolve("continue-pointer.json"),
                masterEnabled && config.syncContinuePointer(), warningLogger, continuePointerNotifier);

        this.worldSyncPreferenceService =
                new WorldSyncPreferenceService(featureConfigDir.resolve("world-sync-preferences.json"), warningLogger);

        Path fingerprintCachePath = featureConfigDir.resolve("world-fingerprint-cache.json");
        String deviceLabel = DeviceLabelResolver.resolve(System.getProperty("user.name"), resolveHostNameOrNull());

        this.worldSyncStatusTracker = new WorldSyncStatusTracker();

        this.worldSaveSyncService = new WorldSaveSyncService(
                archiveStore, cloudFileStore, worldSyncPreferenceService, worker, fingerprintCachePath,
                deviceLabel, config.maxWorldArchiveSizeMb(), config.allowSelectiveFallback(), warningLogger, playerNotifier,
                worldSyncStatusTracker);
        this.worldRestoreService =
                new WorldRestoreService(archiveStore, worldSyncPreferenceService, worker, savesDirectory, warningLogger);
        this.cloudOnlyWorldsFacade = new CloudOnlyWorldsFacade(fingerprintCachePath, warningLogger);
    }

    /**
     * Applies the client-startup checkpoint (FR0.3): reconciles/loads every
     * group. Call once, early in client startup.
     */
    public void reconcileAtStartup() {
        worldSyncPreferenceService.load();

        boolean settingsSyncEnabled = config.enabled() && config.syncSettings();
        for (CloudSyncable syncable : cloudSyncables) {
            CloudSyncableReconciler.reconcileAtStartup(
                    cloudFileStore, cloudSyncableFileName(syncable), syncable, settingsSyncEnabled, warningLogger);
        }

        bookmarkedServersService.reconcileAtStartup();
        notesService.reconcileAtStartup();
        lastPlayedPointerService.reconcileAtStartup();
        worldSaveSyncService.pullFingerprintsAtStartup();
    }

    /**
     * Applies the client-shutdown checkpoint (FR0.3): pushes every group's
     * current state to Cloud, then shuts down the background worker. Call
     * once, on client stop.
     */
    public void syncOnShutdown() {
        boolean settingsSyncEnabled = config.enabled() && config.syncSettings();
        for (CloudSyncable syncable : cloudSyncables) {
            CloudSyncableReconciler.pushOnShutdown(
                    cloudFileStore, cloudSyncableFileName(syncable), syncable, settingsSyncEnabled, warningLogger);
        }

        bookmarkedServersService.syncOnShutdown();
        notesService.syncOnShutdown();
        lastPlayedPointerService.syncOnShutdown();
        worker.shutdown();
    }

    public BookmarkedServersService bookmarkedServersService() {
        return bookmarkedServersService;
    }

    public NotesService notesService() {
        return notesService;
    }

    public LastPlayedPointerService lastPlayedPointerService() {
        return lastPlayedPointerService;
    }

    public WorldSyncPreferenceService worldSyncPreferenceService() {
        return worldSyncPreferenceService;
    }

    public WorldSyncStatusTracker worldSyncStatusTracker() {
        return worldSyncStatusTracker;
    }

    public WorldSaveSyncService worldSaveSyncService() {
        return worldSaveSyncService;
    }

    public WorldRestoreService worldRestoreService() {
        return worldRestoreService;
    }

    public CloudOnlyWorldsHook cloudOnlyWorldsFacade() {
        return cloudOnlyWorldsFacade;
    }

    /** @return the shared background worker; the platform composition root pumps it once per client tick. */
    public CloudSyncWorker cloudSyncWorker() {
        return worker;
    }

    private static String cloudSyncableFileName(CloudSyncable syncable) {
        return "lazuli-cloudsync-" + syncable.cloudSyncId() + ".dat";
    }

    private static String resolveHostNameOrNull() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }
}
