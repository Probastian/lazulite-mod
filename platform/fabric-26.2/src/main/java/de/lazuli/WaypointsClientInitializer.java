package de.lazuli;

import de.lazuli.api.waypoints.WaypointScopeResolver;
import de.lazuli.features.waypoints.config.WaypointsConfigIO;
import de.lazuli.features.waypoints.config.WaypointsFile;
import de.lazuli.features.waypoints.services.ScopeKeySlugger;
import de.lazuli.features.waypoints.services.WaypointRegistry;
import de.lazuli.waypoints.WaypointEngineHandoff;
import de.lazuli.waypoints.WaypointsBundle;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-only composition root for the Waypoints feature (spec Architecture):
 * resolves {@code config/waypoints/} as the per-scope-file directory (R6),
 * constructs {@link WaypointRegistry} with a write-through save callback,
 * implements {@link WaypointScopeResolver} inline (singleplayer: the
 * integrated server's own save-folder name, mirroring {@code
 * SteamCloudSyncClientInitializer#singleplayerWorldInfo}'s identical
 * {@code LevelResource.ROOT}-based resolution; multiplayer: {@code
 * Minecraft.getCurrentServer().ip}, matching {@code
 * LastPlayedPointerService.recordServerJoined}'s own usage), registers its
 * own independent {@code ClientPlayConnectionEvents.JOIN}/{@code DISCONNECT}
 * listeners that call {@link WaypointRegistry#loadScope}/{@link
 * WaypointRegistry#unloadScope} (R9 -- deliberately not shared with {@code
 * SteamCloudSyncClientInitializer}'s own listeners on the same events, per
 * spec Architecture: "two independent features reacting to the same
 * lifecycle event"), and publishes both a {@link WaypointsBundle} (for the
 * Waypoint Manager panel, via {@link WaypointRegistryHandoff}) and the same
 * registry as a {@code WaypointCompassHook} (for the compass-bar HUD mixin,
 * via {@link WaypointEngineHandoff}).
 *
 * <p>Registered <strong>before</strong> {@code MainMenuClientInitializer} in
 * this module's {@code fabric.mod.json} {@code "client"} entrypoint array
 * (same ordering constraint {@code TweaksClientInitializer} already
 * satisfies).
 */
public final class WaypointsClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path waypointsDir = configDir.resolve("waypoints");
        WaypointsConfigIO configIO = new WaypointsConfigIO();

        FabricWaypointScopeResolver scopeResolver = new FabricWaypointScopeResolver();

        WaypointRegistry registry = new WaypointRegistry(
                file -> saveScope(configIO, waypointsDir, file),
                scopeResolver::currentDimensionId);

        WaypointEngineHandoff.publish(registry);
        WaypointRegistryHandoff.publish(new WaypointsBundle(registry, scopeResolver));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                onPlayJoin(scopeResolver, registry, configIO, waypointsDir));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> registry.unloadScope());
    }

    private static void onPlayJoin(FabricWaypointScopeResolver scopeResolver, WaypointRegistry registry,
                                    WaypointsConfigIO configIO, Path waypointsDir) {
        String scopeKey = scopeResolver.currentScopeKey();
        if (scopeKey == null) {
            return;
        }
        Path path = scopeFilePath(waypointsDir, scopeKey);
        WaypointsConfigIO.ParseResult result = configIO.load(path, scopeKey);
        if (result.warning() != null) {
            LazuliMod.LOGGER.warn(result.warning());
        }
        registry.loadScope(scopeKey, result.file());
    }

    private static void saveScope(WaypointsConfigIO configIO, Path waypointsDir, WaypointsFile file) {
        if (file.scopeKey() == null) {
            return;
        }
        Path path = scopeFilePath(waypointsDir, file.scopeKey());
        try {
            Files.createDirectories(waypointsDir);
            Files.writeString(path, configIO.serialize(file));
        } catch (IOException e) {
            LazuliMod.LOGGER.warn("Failed to persist waypoints for scope \"" + file.scopeKey() + "\": " + e);
        }
    }

    private static Path scopeFilePath(Path waypointsDir, String scopeKey) {
        return waypointsDir.resolve(ScopeKeySlugger.slug(scopeKey) + ".json");
    }

    /**
     * R3: singleplayer scope key = the world's save-folder name; multiplayer
     * scope key = the server's own {@code host:port} address string.
     */
    private static final class FabricWaypointScopeResolver implements WaypointScopeResolver {

        @Override
        public String currentScopeKey() {
            Minecraft client = Minecraft.getInstance();
            if (client.hasSingleplayerServer()) {
                return singleplayerWorldSlug(client);
            }
            if (client.getCurrentServer() != null) {
                return client.getCurrentServer().ip;
            }
            return null;
        }

        @Override
        public String currentDimensionId() {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null) {
                return null;
            }
            return client.level.dimension().identifier().toString();
        }

        private static String singleplayerWorldSlug(Minecraft client) {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) {
                return null;
            }
            try {
                return server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
            } catch (RuntimeException e) {
                LazuliMod.LOGGER.warn("Failed to resolve singleplayer world slug for Waypoints: " + e);
                return null;
            }
        }
    }
}
