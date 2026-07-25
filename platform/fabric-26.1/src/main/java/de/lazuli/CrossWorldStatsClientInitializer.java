package de.lazuli;

import de.lazuli.crossworldstats.CrossWorldStatsMergeHook;
import de.lazuli.features.crossworldstats.config.AccountStats;
import de.lazuli.features.crossworldstats.config.CrossWorldStatsConfigIO;
import de.lazuli.features.crossworldstats.services.CrossWorldStatsService;
import de.lazuli.services.steamworks.SteamFriendsGateway;
import de.lazuli.services.steamworks.SteamworksService;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.Map;

/**
 * Client-only composition root for the Cross-World Stats feature on this
 * platform module. Obtains the shared {@link SteamworksService}/
 * {@link SteamFriendsGateway} via their hand-offs (never re-initializes
 * Steamworks), loads this feature's single persisted file, resolves the
 * current Steam account key (FR1.1/FR1.2), constructs
 * {@link CrossWorldStatsService}, registers the merge-hook tick (FR2.1) and a
 * final shutdown flush, and publishes {@link CrossWorldStatsBridgeHandoff}
 * for a future consumer.
 *
 * <p>Must run after {@code SteamworksClientInitializer} (needs both
 * hand-offs); relative order to every other feature initializer is not
 * load-bearing -- this feature has no hard dependency on
 * {@code features/steam-cloud-sync} (Decision 3, soft fallback only), and
 * publishes its own facade for a not-yet-built {@code features/main-menu}
 * Statistics tab to consume later.
 */
public final class CrossWorldStatsClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SteamworksService steamworksService = SteamworksServiceHandoff.require();
        SteamFriendsGateway gateway = SteamFriendsGatewayHandoff.require();

        Path configFilePath = FabricLoader.getInstance().getConfigDir().resolve("cross-world-stats.json");
        CrossWorldStatsConfigIO configIO = new CrossWorldStatsConfigIO();
        CrossWorldStatsConfigIO.ParseResult loaded = configIO.load(configFilePath);
        if (loaded.warning() != null) {
            LazuliMod.LOGGER.warn(loaded.warning());
        }
        Map<String, AccountStats> initialAccounts = loaded.accounts();

        // FR1.1/FR1.2: key by localSteamId64() when Steam is available and
        // resolves a real id; otherwise every offline/non-Steam session on
        // this machine shares the "offline" sentinel bucket (Decision 6).
        long steamId64 = steamworksService.isSteamAvailable() ? gateway.localSteamId64() : 0L;
        String accountKey = steamId64 == 0L ? "offline" : Long.toString(steamId64);

        CrossWorldStatsService service = new CrossWorldStatsService(
                accountKey, initialAccounts, configFilePath, configIO, LazuliMod.LOGGER::warn);
        CrossWorldStatsMergeHook mergeHook = new CrossWorldStatsMergeHook(service);

        CrossWorldStatsBridgeHandoff.publish(service);

        // BF-4-3: capture the world id at join time, while the world/server
        // state is still fully valid -- resolveWorldId() called fresh at
        // DISCONNECT/CLIENT_STOPPING can no longer see the integrated server
        // as running (its connection close is what triggers those events in
        // the first place), so CrossWorldStatsMergeHook falls back to this
        // cached id for its final flush instead of silently no-op'ing.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> mergeHook.onJoin(client));
        ClientTickEvents.END_CLIENT_TICK.register(mergeHook::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mergeHook::flush);
        // BF-4-2: flush the final partial merge interval at the moment a
        // singleplayer world is actually exited (DISCONNECT fires before the
        // server/level is torn down, i.e. while CrossWorldStatsMergeHook's
        // own worldId resolution can still resolve the just-exited world) --
        // CLIENT_STOPPING above only helps if the game process itself is
        // closed while still inside a world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> mergeHook.flush(client));
    }
}
