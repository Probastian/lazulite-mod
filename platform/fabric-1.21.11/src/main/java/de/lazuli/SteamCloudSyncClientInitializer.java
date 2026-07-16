package de.lazuli;

import de.lazuli.api.cloudsync.CloudSyncable;
import de.lazuli.cloudsync.FabricBookmarkToggleInjector;
import de.lazuli.cloudsync.FabricCloudOnlyWorldListInjector;
import de.lazuli.cloudsync.FabricWorldSyncToggleInjector;
import de.lazuli.features.helloworldmainmenu.config.HelloWorldMainMenuConfigIO;
import de.lazuli.features.steamcloudsync.api.SteamCloudSyncConfig;
import de.lazuli.features.steamcloudsync.config.SteamCloudSyncConfigIO;
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
 * {@code HelloWorldMainMenuClientInitializer} and
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

        Path helloWorldConfigPath = configDir.resolve("hello-world-main-menu.json");
        List<CloudSyncable> cloudSyncables = List.of(new HelloWorldMainMenuCloudSyncAdapter(helloWorldConfigPath));

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
        new FabricWorldSyncToggleInjector(coordinator.worldSyncPreferenceService(),
                () -> config.enabled() && steamworksService.isSteamAvailable());
        new FabricCloudOnlyWorldListInjector(coordinator.cloudOnlyWorldsFacade(), coordinator.worldRestoreService());
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
                coordinator.worldSaveSyncService().onWorldUnload(info.worldSlug(), info.worldFolder(), info.displayName());
            });
        } else if (client.getCurrentServerEntry() != null) {
            ServerInfo server = client.getCurrentServerEntry();
            coordinator.lastPlayedPointerService().recordServerDisconnected(server.name, server.address);
        }
    }

    private Optional<SingleplayerWorldInfo> singleplayerWorldInfo(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) {
            return Optional.empty();
        }
        try {
            Path worldFolder = server.getSavePath(WorldSavePath.ROOT);
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
     * Bridges {@code features/hello-world-main-menu}'s own config file into
     * this feature's {@link CloudSyncable} contract (Group 1, FR1.1/FR1.2),
     * per Decision 2: a private nested class inside this entrypoint itself,
     * not a separate Version Adapter file.
     */
    private static final class HelloWorldMainMenuCloudSyncAdapter implements CloudSyncable {

        private final Path configPath;
        private final HelloWorldMainMenuConfigIO configIO = new HelloWorldMainMenuConfigIO();

        private HelloWorldMainMenuCloudSyncAdapter(Path configPath) {
            this.configPath = configPath;
        }

        @Override
        public String cloudSyncId() {
            return "hello-world-main-menu";
        }

        @Override
        public byte[] exportState() {
            HelloWorldMainMenuConfigIO.ParseResult result = configIO.load(configPath);
            return configIO.serialize(result.config()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void importState(byte[] data) {
            HelloWorldMainMenuConfigIO.ParseResult result = configIO.parse(new String(data, StandardCharsets.UTF_8));
            if (result.warning() != null) {
                LazuliMod.LOGGER.warn(result.warning());
                return;
            }
            try {
                Path parent = configPath.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(configPath, configIO.serialize(result.config()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LazuliMod.LOGGER.warn("Failed to import Steam Cloud state for hello-world-main-menu: {}", e.toString());
            }
        }

        @Override
        public long localLastModifiedMillis() {
            try {
                return Files.exists(configPath) ? Files.getLastModifiedTime(configPath).toMillis() : -1L;
            } catch (IOException e) {
                return -1L;
            }
        }
    }
}
