package de.lazuli;

import de.lazuli.api.cloudsync.CloudSyncable;
import de.lazuli.cloudsync.FabricBookmarkToggleInjector;
import de.lazuli.cloudsync.FabricCloudOnlyWorldListInjector;
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

import java.nio.file.Path;
import java.util.List;

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

        List<CloudSyncable> cloudSyncables = List.of();

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
            LazuliMod.LOGGER.info("Steam Cloud world-sync toggle icon activated (per-row icon should now render on the Singleplayer screen).");
        } else {
            LazuliMod.LOGGER.info(
                    "Steam Cloud world-sync toggle icon NOT activated (config.enabled={}, isSteamAvailable={}) -- no icon will render.",
                    config.enabled(), steamworksService.isSteamAvailable());
        }
        new FabricCloudOnlyWorldListInjector(coordinator.cloudOnlyWorldsFacade(), coordinator.worldRestoreService());
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
