package de.lazuli;

import de.lazuli.api.cloudsync.CloudSyncable;
import de.lazuli.cloudsync.CrossWorldStatsOfflineBucketFilter;
import de.lazuli.cloudsync.FabricBookmarkToggleInjector;
import de.lazuli.cloudsync.WaypointsDirectoryBundleMerger;
import de.lazuli.common.config.MainMenuJson;
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

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

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
                new CrossWorldStatsCloudSyncAdapter(configDir.resolve("cross-world-stats.json")),
                new TweaksJsonCloudSyncAdapter(configDir.resolve("tweaks.json")),
                new WaypointsJsonCloudSyncAdapter(configDir.resolve("waypoints")));

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
            WorldCloudMigrationHolder.publish(coordinator.worldCloudMigrationService());
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

    /**
     * Waypoints spec R23: {@code config/waypoints/} is synced as one
     * bundled envelope enumerating every scope-keyed file currently present
     * (scope file name -&gt; raw JSON text), rather than wrapping one fixed
     * path the way every other adapter in this class does -- {@link
     * CloudSyncable}/{@link CloudSyncCoordinator} expect one fixed,
     * mod-init-time-known file per adapter, while Waypoints' files are
     * one-per-scope-key and only exist locally once a given scope has
     * actually been visited on this device (spec Architecture). The
     * risk-critical merge rule -- an import must never delete/clobber a
     * local-only scope's file the incoming envelope simply doesn't mention
     * -- is a separate, directly-unit-tested pure function, {@link
     * WaypointsDirectoryBundleMerger}, mirroring {@link
     * CrossWorldStatsOfflineBucketFilter}'s own precedent for the identical
     * reason (implementation plan Risk #3).
     */
    private static final class WaypointsJsonCloudSyncAdapter implements CloudSyncable {
        private final Path waypointsDir;

        private WaypointsJsonCloudSyncAdapter(Path waypointsDir) {
            this.waypointsDir = waypointsDir;
        }

        @Override
        public String cloudSyncId() {
            return "waypoints";
        }

        @Override
        public byte[] exportState() {
            return encodeEnvelope(readLocalFiles());
        }

        @Override
        public void importState(byte[] data) {
            Map<String, String> incoming = decodeEnvelope(data);
            Map<String, String> local = readLocalFiles();
            Map<String, String> merged = WaypointsDirectoryBundleMerger.mergeForImport(local, incoming);
            writeLocalFiles(merged);
        }

        @Override
        public long localLastModifiedMillis() {
            if (!Files.isDirectory(waypointsDir)) {
                return -1L;
            }
            try (java.util.stream.Stream<Path> stream = Files.list(waypointsDir)) {
                return stream.filter(p -> p.toString().endsWith(".json"))
                        .mapToLong(p -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException e) {
                                return -1L;
                            }
                        })
                        .max()
                        .orElse(-1L);
            } catch (IOException e) {
                return -1L;
            }
        }

        private Map<String, String> readLocalFiles() {
            Map<String, String> result = new java.util.LinkedHashMap<>();
            if (!Files.isDirectory(waypointsDir)) {
                return result;
            }
            try (java.util.stream.Stream<Path> stream = Files.list(waypointsDir)) {
                for (Path path : (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".json"))::iterator) {
                    try {
                        result.put(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        LazuliMod.LOGGER.warn("Failed to read waypoints file for Cloud export: {}", e.toString());
                    }
                }
            } catch (IOException e) {
                LazuliMod.LOGGER.warn("Failed to list config/waypoints/ for Cloud export: {}", e.toString());
            }
            return result;
        }

        private void writeLocalFiles(Map<String, String> files) {
            try {
                Files.createDirectories(waypointsDir);
            } catch (IOException e) {
                LazuliMod.LOGGER.warn("Failed to create config/waypoints/ for Cloud import: {}", e.toString());
                return;
            }
            for (Map.Entry<String, String> entry : files.entrySet()) {
                try {
                    Files.writeString(waypointsDir.resolve(entry.getKey()), entry.getValue());
                } catch (IOException e) {
                    LazuliMod.LOGGER.warn("Failed to write waypoints file \"" + entry.getKey() + "\" from Cloud import: " + e);
                }
            }
        }

        private static byte[] encodeEnvelope(Map<String, String> files) {
            MainMenuJson.JsonObject root = new MainMenuJson.JsonObject();
            for (Map.Entry<String, String> entry : files.entrySet()) {
                root.putString(entry.getKey(), entry.getValue());
            }
            return MainMenuJson.write(root).getBytes(StandardCharsets.UTF_8);
        }

        private static Map<String, String> decodeEnvelope(byte[] data) {
            Map<String, String> result = new java.util.LinkedHashMap<>();
            if (data == null || data.length == 0) {
                return result;
            }
            try {
                MainMenuJson.JsonValue value = MainMenuJson.parse(new String(data, StandardCharsets.UTF_8));
                if (!(value instanceof MainMenuJson.JsonObject root)) {
                    return result;
                }
                for (Map.Entry<String, MainMenuJson.JsonValue> entry : root.members().entrySet()) {
                    if (entry.getValue() instanceof MainMenuJson.JsonString s) {
                        result.put(entry.getKey(), s.value());
                    }
                }
            } catch (RuntimeException e) {
                LazuliMod.LOGGER.warn("Malformed Waypoints Cloud payload, ignoring: {}", e.toString());
            }
            return result;
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
                coordinator.worldSaveSyncService().onWorldUnload(info.worldSlug(), info.worldFolder(), info.displayName(),
                        () -> readLevelDatBatch(info.worldSlug()));
            });
        } else if (client.getCurrentServer() != null) {
            coordinator.lastPlayedPointerService().recordServerDisconnected(client.getCurrentServer().name, client.getCurrentServer().ip);
        }
        // FR-T.4: return-to-main-menu is, for this mod's purposes, the same
        // event as disconnect (F14) -- refresh Cloud metadata (fingerprint
        // cache/cloud-only-world list) here too, alongside the existing
        // upload trigger above. Metadata-only; never pulls a full archive.
        coordinator.onReturnToMainMenu();
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

    /**
     * cloud-world-metadata-file gap fix: the {@code onWorldUnload} checkpoint's
     * real {@code level.dat} NBT read, invoked lazily on
     * {@code WorldSaveSyncService}'s background worker thread (never on the
     * client tick thread that fires {@code onPlayDisconnect}) -- safe here
     * because by the time this checkpoint fires the integrated server has
     * already fully unloaded/disconnected (per
     * {@code WorldSaveSyncService#onWorldUnload}'s own Javadoc), so a fresh
     * {@link LevelStorageSource.LevelStorageAccess} does not race any
     * still-held session lock. Mirrors {@code WorldsPanel}'s own
     * {@code readLevelDatBatch} read shape (same {@code Dynamic<?>} root/
     * {@code "Data"} sub-fields), duplicated here rather than shared since
     * {@code WorldsPanel} is a client-menu-only type this background-thread
     * call site should not depend on.
     */
    private static WorldConflictResolutionHook.LevelDatBatch readLevelDatBatch(String worldSlug) {
        try (LevelStorageSource.LevelStorageAccess access = Minecraft.getInstance().getLevelSource().createAccess(worldSlug)) {
            com.mojang.serialization.Dynamic<?> root = access.getUnfixedDataTagWithFallback();
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
            // root already opened above, no second LevelStorageAccess.
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
