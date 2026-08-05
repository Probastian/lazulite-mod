package de.lazuli;

import de.lazuli.api.cloudsync.CloudSyncable;
import de.lazuli.cloudsync.CrossWorldStatsOfflineBucketFilter;
import de.lazuli.cloudsync.FabricBookmarkToggleInjector;
import de.lazuli.features.crossworldstats.config.AccountStats;
import de.lazuli.features.crossworldstats.config.CrossWorldStatsConfigIO;
import de.lazuli.features.steamcloudsync.api.SteamCloudSyncConfig;
import de.lazuli.features.steamcloudsync.config.SteamCloudSyncConfigIO;
import de.lazuli.api.cloudsync.WorldConflictResolutionHook;
import de.lazuli.features.steamcloudsync.services.CloudSyncCoordinator;
import de.lazuli.services.steamworks.SteamworksService;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client-only composition root for the Steam Cloud Sync feature on this
 * platform module.
 *
 * <p>Mirrors the 26.x/26.1 composition roots exactly (same wiring shape,
 * same checkpoints), just under this version's mapped names -- see
 * {@code .claude/context/minecraft.md}'s Known Cross-Version API Differences
 * table for the confirmed divergences this class had to account for
 * (singleplayer detection, world save-path resolution, rendering).
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
                new CrossWorldStatsCloudSyncAdapter(configDir.resolve("cross-world-stats.json")),
                new TweaksJsonCloudSyncAdapter(configDir.resolve("tweaks.json")));

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
            WorldSaveHookHolder.publish(coordinator.worldSaveSyncService());
            WorldFreshnessHookHolder.publish(coordinator.worldSaveSyncService());
            WorldConflictHookHolder.publish(coordinator.worldSaveSyncService());
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
     * {@link CloudSyncable} in this list is already subject to. See
     * spec FR-B.5/plan Decision 5/Risk R2 for this adapter's fresh-install
     * ordering caveat.
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
     * FR-C.1/FR-C.2: {@code servers.dat}, synced as an opaque byte blob,
     * unconditionally, same shape/caveats as {@link OptionsTxtCloudSyncAdapter}.
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
     * FR1/FR2: {@code config/tweaks.json} is synced as an opaque byte blob,
     * unconditionally (no config toggle) -- same shape/caveats as
     * {@link OptionsTxtCloudSyncAdapter} (FR3: no init-order coupling with
     * {@code TweaksClientInitializer}'s own independent load of the same
     * path is required or introduced).
     */
    private static final class TweaksJsonCloudSyncAdapter implements CloudSyncable {
        private final Path tweaksPath;

        private TweaksJsonCloudSyncAdapter(Path tweaksPath) {
            this.tweaksPath = tweaksPath;
        }

        @Override
        public String cloudSyncId() {
            return "tweaks";
        }

        @Override
        public byte[] exportState() {
            try {
                return Files.exists(tweaksPath) ? Files.readAllBytes(tweaksPath) : new byte[0];
            } catch (IOException e) {
                LazuliMod.LOGGER.warn("Failed to read tweaks.json for Cloud export: {}", e.toString());
                return new byte[0];
            }
        }

        @Override
        public void importState(byte[] data) {
            try {
                Files.write(tweaksPath, data);
            } catch (IOException e) {
                LazuliMod.LOGGER.warn("Failed to write tweaks.json from Cloud import: {}", e.toString());
            }
        }

        @Override
        public long localLastModifiedMillis() {
            try {
                return Files.exists(tweaksPath) ? Files.getLastModifiedTime(tweaksPath).toMillis() : -1L;
            } catch (IOException e) {
                return -1L;
            }
        }
    }

    /**
     * FR-D.1-FR-D.5: {@code config/cross-world-stats.json}, synced via
     * {@link CrossWorldStatsConfigIO}'s typed load/save with FR-D.2's
     * offline-bucket exclusion (NFR1 exception, Risk R3).
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

    private void onPlayJoin(MinecraftClient client, CloudSyncCoordinator coordinator) {
        if (client.isIntegratedServerRunning()) {
            singleplayerWorldInfo(client).ifPresent(info ->
                    coordinator.lastPlayedPointerService().recordWorldEntered(info.displayName(), info.worldSlug()));
        } else if (client.getCurrentServerEntry() != null) {
            ServerInfo server = client.getCurrentServerEntry();
            coordinator.lastPlayedPointerService().recordServerJoined(server.name, server.address);
        }
    }

    private void onPlayDisconnect(MinecraftClient client, CloudSyncCoordinator coordinator) {
        if (client.isIntegratedServerRunning()) {
            singleplayerWorldInfo(client).ifPresent(info -> {
                coordinator.lastPlayedPointerService().recordWorldExited(info.displayName(), info.worldSlug());
                coordinator.worldSaveSyncService().onWorldUnload(info.worldSlug(), info.worldFolder(), info.displayName(),
                        () -> readLevelDatBatch(info.worldSlug()));
            });
        } else if (client.getCurrentServerEntry() != null) {
            ServerInfo server = client.getCurrentServerEntry();
            coordinator.lastPlayedPointerService().recordServerDisconnected(server.name, server.address);
        }
        // FR-T.4: return-to-main-menu is, for this mod's purposes, the same
        // event as disconnect (F14) -- refresh Cloud metadata (fingerprint
        // cache/cloud-only-world list) here too, alongside the existing
        // upload trigger above. Metadata-only; never pulls a full archive.
        coordinator.onReturnToMainMenu();
    }

    private Optional<SingleplayerWorldInfo> singleplayerWorldInfo(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) {
            return Optional.empty();
        }
        try {
            Path worldFolder = server.getSavePath(WorldSavePath.ROOT).normalize();
            String worldSlug = worldFolder.getFileName().toString();
            String displayName = server.getSaveProperties().getLevelName();
            return Optional.of(new SingleplayerWorldInfo(worldSlug, displayName, worldFolder));
        } catch (RuntimeException e) {
            LazuliMod.LOGGER.warn("Failed to resolve singleplayer world info for Steam Cloud Sync: {}", e.toString());
            return Optional.empty();
        }
    }

    private record SingleplayerWorldInfo(String worldSlug, String displayName, Path worldFolder) {
    }

    /**
     * cloud-world-metadata-file gap fix: the {@code onWorldUnload} checkpoint's
     * real {@code level.dat} NBT read, invoked lazily on
     * {@code WorldSaveSyncService}'s background worker thread (never on the
     * client tick thread that fires {@code onPlayDisconnect}) -- safe here
     * because by the time this checkpoint fires the integrated server has
     * already fully unloaded/disconnected (per
     * {@code WorldSaveSyncService#onWorldUnload}'s own Javadoc), so a fresh
     * {@code LevelStorage.Session} does not race any still-held session
     * lock. Mirrors {@code WorldsPanel}'s own {@code readLevelDatBatch} read
     * shape (Yarn/1.21.11's {@code LevelStorage.Session.readLevelProperties()}
     * equivalent of the Mojang-mapped
     * {@code LevelStorageAccess.getUnfixedDataTagWithFallback()}, see
     * {@code minecraft.md}'s Known Cross-Version API Differences table),
     * duplicated here rather than shared since {@code WorldsPanel} is a
     * client-menu-only type this background-thread call site should not
     * depend on.
     */
    private static WorldConflictResolutionHook.LevelDatBatch readLevelDatBatch(String worldSlug) {
        try (net.minecraft.world.level.storage.LevelStorage.Session session =
                MinecraftClient.getInstance().getLevelStorage().createSession(worldSlug)) {
            com.mojang.serialization.Dynamic<?> root = session.readLevelProperties();
            com.mojang.serialization.Dynamic<?> data = root.get("Data").orElseEmptyMap();
            Long seed = data.get("WorldGenSettings").get("seed").asNumber().result().map(Number::longValue).orElse(null);
            int difficultyId = data.get("Difficulty").asNumber().result().map(Number::intValue).orElse(-1);
            String difficulty = switch (difficultyId) {
                case 0 -> "Peaceful";
                case 1 -> "Easy";
                case 2 -> "Normal";
                case 3 -> "Hard";
                default -> null;
            };
            Boolean cheatsEnabled = data.get("allowCommands").asBoolean().result().orElse(null);
            long dayTimeTicks = data.get("DayTime").asNumber().result().map(Number::longValue).orElse(-1L);
            long dayCount = dayTimeTicks >= 0 ? dayTimeTicks / 24000L : -1L;
            String minecraftVersion = data.get("Version").get("Name").asString().result().orElse(null);
            // cloud-world-entry-parity Requirement 3b: the ordinary
            // background-sync checkpoint's own real lastPlayedMillis/
            // gameMode/hardcore reads -- reusing the exact same Dynamic<?>
            // root already opened above, no second LevelStorage.Session.
            long lastPlayedMillis = data.get("LastPlayed").asNumber().result().map(Number::longValue).orElse(-1L);
            int gameTypeId = data.get("GameType").asNumber().result().map(Number::intValue).orElse(-1);
            String gameMode = switch (gameTypeId) {
                case 0 -> "Survival";
                case 1 -> "Creative";
                case 2 -> "Adventure";
                case 3 -> "Spectator";
                default -> null;
            };
            boolean hardcore = data.get("hardcore").asBoolean().result().orElse(false);
            return new WorldConflictResolutionHook.LevelDatBatch(seed, difficulty, cheatsEnabled, dayCount, minecraftVersion, true,
                    lastPlayedMillis, gameMode, hardcore);
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to read level.dat batch for world \"" + worldSlug + "\" at unload: " + e);
            return WorldConflictResolutionHook.LevelDatBatch.unreadable();
        }
    }
}
