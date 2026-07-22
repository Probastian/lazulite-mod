package de.lazuli;

import de.lazuli.api.richpresence.RichPresenceFacade;
import de.lazuli.features.richpresence.api.RichPresenceConfig;
import de.lazuli.features.richpresence.config.RichPresenceConfigIO;
import de.lazuli.features.richpresence.services.LocalPresenceTracker;
import de.lazuli.features.richpresence.services.LocalPresenceTrackerImpl;
import de.lazuli.features.richpresence.services.NoopLocalPresenceTracker;
import de.lazuli.features.richpresence.services.RichPresenceFacadeImpl;
import de.lazuli.features.richpresence.services.RichPresencePublisher;
import de.lazuli.richpresence.MinecraftTierTextFormatter;
import de.lazuli.richpresence.PresenceSignalGatherer;
import de.lazuli.services.steamworks.SteamFriendsGateway;
import de.lazuli.services.steamworks.SteamworksService;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.BlockItem;
import net.minecraft.util.ActionResult;

import java.nio.file.Path;

/**
 * Client-only composition root for the Rich Presence Publishing feature on
 * this platform module. Obtains the shared {@link SteamworksService}/
 * {@link SteamFriendsGateway} via their hand-offs (never re-initializes
 * Steamworks), loads this feature's config, and -- when Steam is available
 * and the feature is enabled -- constructs the plain-JVM core
 * ({@code PresenceStatusResolver} via {@code LocalPresenceTrackerImpl}),
 * this module's {@link PresenceSignalGatherer}/{@link MinecraftTierTextFormatter}
 * pair, registers the per-tick sweep, and publishes {@link RichPresenceFacadeHandoff}
 * (FR-RP6). Otherwise publishes a {@code Noop} facade.
 *
 * <p>Registered after {@code SteamworksClientInitializer} in this module's
 * {@code fabric.mod.json} {@code "client"} array (needs both Steamworks
 * hand-offs); relative order to the other feature initializers is not
 * load-bearing.
 */
public final class RichPresenceClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SteamworksService steamworksService = SteamworksServiceHandoff.require();
        SteamFriendsGateway gateway = SteamFriendsGatewayHandoff.require();

        Path configFilePath = FabricLoader.getInstance().getConfigDir().resolve("rich-presence.json");
        RichPresenceConfigIO.ParseResult configResult = new RichPresenceConfigIO().load(configFilePath);
        if (configResult.warning() != null) {
            LazuliMod.LOGGER.warn(configResult.warning());
        }
        RichPresenceConfig config = configResult.config();

        boolean active = steamworksService.isSteamAvailable() && config.enabled();
        if (!active) {
            RichPresenceFacadeHandoff.publish(new RichPresenceFacadeImpl(new NoopLocalPresenceTracker()));
            return;
        }

        PresenceSignalGatherer gatherer = new PresenceSignalGatherer();
        MinecraftTierTextFormatter formatter = new MinecraftTierTextFormatter();
        LocalPresenceTracker tracker = new LocalPresenceTrackerImpl(gatherer::current, formatter);
        RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway);

        RichPresenceFacadeHandoff.publish(new RichPresenceFacadeImpl(tracker));

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player.getStackInHand(hand).getItem() instanceof BlockItem) {
                gatherer.onBlockPlacementAttempt();
            }
            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            gatherer.tick();
            publisher.tick();
        });
    }
}
