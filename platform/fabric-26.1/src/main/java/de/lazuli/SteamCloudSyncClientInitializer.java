package de.lazuli;

import de.lazuli.api.cloudsync.CloudSyncable;
import de.lazuli.cloudsync.CrossWorldStatsOfflineBucketFilter;
import de.lazuli.cloudsync.FabricBookmarkToggleInjector;
import de.lazuli.features.crossworldstats.config.AccountStats;
import de.lazuli.features.crossworldstats.config.CrossWorldStatsConfigIO;
import de.lazuli.features.steamcloudsync.api.SteamCloudSyncConfig;
import de.lazuli.features.steamcloudsync.config.SteamCloudSyncConfigIO;
import de.lazuli.features.steamcloudsync.services.CloudSyncCoordinator;
import de.lazuli.services.steamworks.SteamworksService;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Client-only composition root for the Steam Cloud Sync feature on this
 * platform module.
 *
 * <p>Obtains the already-constructed {@link SteamworksService} via
 * {@link SteamworksServiceHandoff#require()} (resolved during this feature's
 * own planning, Decision 1), loads this feature's own config, builds
 * {@link CloudSyncCoordinator}, reconciles at startup, and registers every
 * FR0.3 checkpoint plus the four Group 3/6 Version Adapters.
 *
 * <p>Registered as the <strong>third</strong> {@code "client"} entrypoint in
 * this module's {@code fabric.mod.json}, after
 * {@code SteamworksClientInitializer} (order is load-bearing -- see
 * {@link SteamworksServiceHandoff}).
 */
public final class SteamCloudSyncClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SteamworksService steamworksService = SteamworksServiceHandoff.require();

        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFilePath = configDir.resolve("steam-cloud-sync.json");
        Path featureConfigDir = configDir.resolve("steam-cloud-sync");
        Path savesDirectory = FabricLoader.getInstance().getGameDir().resolve("saves");

        SteamCloudSyncConfigIO.ParseResult configResult = new SteamCloudSyncConfigIO().load(configFilePath);
        if (configResult.warning() != null) {
            LazuliMod.LOGGER.warn(configResult.warning());
        }
        SteamCloudSyncConfig config = configResult.config();

        Path gameDir = FabricLoader.getInstance().getGameDir();
        List<CloudSyncable> cloudSyncables = List.of(
                new OptionsTxtCloudSyncAdapter(gameDir.resolve("options.txt")),
                new ServersDatCloudSyncAdapter(gameDir.resolve("servers.dat")),
                new CrossWorldStatsCloudSyncAdapter(configDir.resolve("cross-world-stats.json")));

        CloudSyncCoordinator coordinator = new CloudSyncCoordinator(
                steamworksService,
                config,
                cloudSyncables,
                featureConfigDir,
                savesDirectory,
                LazuliMod.LOGGER::warn,
                LazuliMod.LOGGER::info,
                pointer -> LazuliMod.LOGGER.info("A newer Steam Cloud \"continue\" pointer exists: {}", pointer));

        coordinator.reconcileAtStartup();

        ClientTickEvents.END_CLIENT_TICK.register(client -> coordinator.cloudSyncWorker().pumpTickWork());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> coordinator.syncOnShutdown());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onPlayJoin(client, coordinator));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onPlayDisconnect(client, coordinator));

        new FabricBookmarkToggleInjector(coordinator.bookmarkedServersService());
        if (config.enabled() && steamworksService.isSteamAvailable()) {
            WorldSyncToggleHookHolder.publish(coordinator.worldSyncPreferenceService());
            WorldSyncStatusHookHolder.publish(coordinator.worldSyncStatusTracker());
            CloudOnlyWorldsHookHolder.publish(coordinator.cloudOnlyWorldsFacade());
            WorldRestoreHookHolder.publish(coordinator.worldRestoreService());
            LazuliMod.LOGGER.info("Steam Cloud world-sync toggle icon and cloud-only-world restore flow activated "
                    + "(Worlds tab should now render both).");
        } else {
            LazuliMod.LOGGER.info(
                    "Steam Cloud world-sync toggle icon and cloud-only-world restore flow NOT activated "
                            + "(config.enabled={}, isSteamAvailable={}) -- neither will render.",
                    config.enabled(), steamworksService.isSteamAvailable());
        }
    }

    /**
     * FR-B.1/FR-B.2/FR-B.3: {@code options.txt} is synced as an opaque byte
     * blob, unconditionally (no config toggle) -- the only gate is the master
     * {@code enabled}/{@code isSteamAvailable()} check every other
     * {@link CloudSyncable} in this list is already subject to.
     *
     * <p>FR-B.5 ordering caveat: this adapter's startup reconcile runs as part
     * of {@link CloudSyncCoordinator#reconcileAtStartup()} above, which is not
     * guaranteed to run before vanilla's own {@code Options} object is
     * constructed/read for this same launch -- if a fresher Cloud copy is
     * pulled after vanilla has already read the pre-existing local file, the
     * pulled copy takes effect on the *next* launch instead of this one (see
     * spec FR-B.5, plan Decision 5/Risk R2).
     */
    private static final class OptionsTxtCloudSyncAdapter implements CloudSyncable {
        private final Path optionsPath;

        private OptionsTxtCloudSyncAdapter(Path optionsPath) {
            this.optionsPath = optionsPath;
        }

        @Override
        public String cloudSyncId() {
            return "options";
        }

        @Override
        public byte[] exportState() {
            try {
                return Files.exists(optionsPath) ? Files.readAllBytes(optionsPath) : new byte[0];
            } catch (IOException e) {
                LazuliMod.LOGGER.warn("Failed to read options.txt for Cloud export: {}", e.toString());
                return new byte[0];
            }
        }

        @Override
        public void importState(byte[] data) {
            try {
                Files.write(optionsPath, data);
            } catch (IOException e) {
                LazuliMod.LOGGER.warn("Failed to write options.txt from Cloud import: {}", e.toString());
            }
        }

        @Override
        public long localLastModifiedMillis() {
            try {
                return Files.exists(optionsPath) ? Files.getLastModifiedTime(optionsPath).toMillis() : -1L;
            } catch (IOException e) {
                return -1L;
            }
        }
    }

    /**
     * FR-C.1/FR-C.2: {@code servers.dat} (vanilla's NBT-binary server list,
     * distinct from this feature's own {@code BookmarkedServer} list, FR-C.3)
     * is synced as an opaque byte blob, unconditionally, same shape/caveats as
     * {@link OptionsTxtCloudSyncAdapter} (see FR-C.4).
     */
    private static final class ServersDatCloudSyncAdapter implements CloudSyncable {
        private final Path serversPath;

        private ServersDatCloudSyncAdapter(Path serversPath) {
            this.serversPath = serversPath;
        }

        @Override
        public String cloudSyncId() {
            return "servers-dat";
        }

        @Override
        public byte[] exportState() {
            try {
                return Files.exists(serversPath) ? Files.readAllBytes(serversPath) : new byte[0];
            } catch (IOException e) {
                LazuliMod.LOGGER.warn("Failed to read servers.dat for Cloud export: {}", e.toString());
                return new byte[0];
            }
        }

        @Override
        public void importState(byte[] data) {
            try {
                Files.write(serversPath, data);
            } catch (IOException e) {
                LazuliMod.LOGGER.warn("Failed to write servers.dat from Cloud import: {}", e.toString());
            }
        }

        @Override
        public long localLastModifiedMillis() {
            try {
                return Files.exists(serversPath) ? Files.getLastModifiedTime(serversPath).toMillis() : -1L;
            } catch (IOException e) {
                return -1L;
            }
        }
    }

    /**
     * FR-D.1-FR-D.5: {@code config/cross-world-stats.json} is synced via
     * {@link CrossWorldStatsConfigIO}'s typed load/save (not raw bytes, since
     * the FR-D.2 offline-bucket exclusion needs to inspect per-account keys).
     *
     * <p>NFR1: this is the one deliberate, spec-sanctioned exception to
     * {@code CloudSyncable}'s "no Feature-to-Feature dependency" default --
     * confined to this platform-module-local adapter class, not inside
     * {@code features/steam-cloud-sync} itself (Risk R3). The offline-bucket
     * filter/merge logic itself is a separate, directly-unit-tested pure
     * function, {@link CrossWorldStatsOfflineBucketFilter} (NFR4).
     */
    private static final class CrossWorldStatsCloudSyncAdapter implements CloudSyncable {
        private final Path statsPath;
        private final CrossWorldStatsConfigIO configIO = new CrossWorldStatsConfigIO();

        private CrossWorldStatsCloudSyncAdapter(Path statsPath) {
            this.statsPath = statsPath;
        }

        @Override
        public String cloudSyncId() {
            return "cross-world-stats";
        }

        @Override
        public byte[] exportState() {
            Map<String, AccountStats> local = loadLocal();
            Map<String, AccountStats> exportable = CrossWorldStatsOfflineBucketFilter.filterForExport(local);
            return configIO.serialize(exportable).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void importState(byte[] data) {
            CrossWorldStatsConfigIO.ParseResult incomingResult = configIO.parse(new String(data, StandardCharsets.UTF_8));
            if (incomingResult.warning() != null) {
                LazuliMod.LOGGER.warn("Malformed Cross-World Stats Cloud payload, ignoring: {}", incomingResult.warning());
                return;
            }
            Map<String, AccountStats> local = loadLocal();
            Map<String, AccountStats> merged = CrossWorldStatsOfflineBucketFilter.mergeForImport(local, incomingResult.accounts());
            String warning = configIO.save(statsPath, merged);
            if (warning != null) {
                LazuliMod.LOGGER.warn(warning);
            }
        }

        @Override
        public long localLastModifiedMillis() {
            try {
                return Files.exists(statsPath) ? Files.getLastModifiedTime(statsPath).toMillis() : -1L;
            } catch (IOException e) {
                return -1L;
            }
        }

        private Map<String, AccountStats> loadLocal() {
            CrossWorldStatsConfigIO.ParseResult loaded = configIO.load(statsPath);
            if (loaded.warning() != null) {
                LazuliMod.LOGGER.warn(loaded.warning());
            }
            return loaded.accounts();
        }
    }

    private void onPlayJoin(Minecraft client, CloudSyncCoordinator coordinator) {
        if (client.hasSingleplayerServer()) {
            singleplayerWorldInfo(client).ifPresent(info ->
                    coordinator.lastPlayedPointerService().recordWorldEntered(info.displayName(), info.worldSlug()));
        } else if (client.getCurrentServer() != null) {
            coordinator.lastPlayedPointerService().recordServerJoined(client.getCurrentServer().name, client.getCurrentServer().ip);
        }
    }

    private void onPlayDisconnect(Minecraft client, CloudSyncCoordinator coordinator) {
        if (client.hasSingleplayerServer()) {
            singleplayerWorldInfo(client).ifPresent(info -> {
                coordinator.lastPlayedPointerService().recordWorldExited(info.displayName(), info.worldSlug());
                coordinator.worldSaveSyncService().onWorldUnload(info.worldSlug(), info.worldFolder(), info.displayName());
            });
        } else if (client.getCurrentServer() != null) {
            coordinator.lastPlayedPointerService().recordServerDisconnected(client.getCurrentServer().name, client.getCurrentServer().ip);
        }
    }

    private java.util.Optional<SingleplayerWorldInfo> singleplayerWorldInfo(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return java.util.Optional.empty();
        }
        try {
            Path worldFolder = server.getWorldPath(LevelResource.ROOT).normalize();
            String worldSlug = worldFolder.getFileName().toString();
            String displayName = server.getWorldData().getLevelName();
            return java.util.Optional.of(new SingleplayerWorldInfo(worldSlug, displayName, worldFolder));
        } catch (RuntimeException e) {
            LazuliMod.LOGGER.warn("Failed to resolve singleplayer world info for Steam Cloud Sync: {}", e.toString());
            return java.util.Optional.empty();
        }
    }

    private record SingleplayerWorldInfo(String worldSlug, String displayName, Path worldFolder) {
    }
}
