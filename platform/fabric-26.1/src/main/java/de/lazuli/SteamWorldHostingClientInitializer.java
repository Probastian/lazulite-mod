package de.lazuli;

import de.lazuli.api.worldhosting.FriendHostingStatusReader;
import de.lazuli.api.worldhosting.WorldJoinRequester;
import de.lazuli.features.worldhosting.api.SteamWorldHostingConfig;
import de.lazuli.features.worldhosting.config.SteamWorldHostingConfigIO;
import de.lazuli.features.worldhosting.services.ConnectStringCodec;
import de.lazuli.features.worldhosting.services.HostGateway;
import de.lazuli.features.worldhosting.services.HostingLifecycle;
import de.lazuli.features.worldhosting.services.HostingPresenceScanner;
import de.lazuli.features.worldhosting.services.NoopFriendHostingStatusReader;
import de.lazuli.features.worldhosting.services.NoopWorldJoinRequester;
import de.lazuli.services.steamworks.SteamFriendsGateway;
import de.lazuli.services.steamworks.SteamworksService;
import de.lazuli.worldhosting.SteamAmbientSession;
import de.lazuli.worldhosting.WorldHostingHookHolder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * Client-only composition root for the Steam World Hosting feature on this
 * platform module. Obtains the shared {@link SteamworksService} and
 * {@link SteamFriendsGateway} via their hand-offs (never re-initializes
 * Steamworks), loads this feature's config, and -- when Steam is available and
 * the feature is enabled -- constructs the hosting service set, publishes the
 * mixin hook holder and the Friends Sidebar bridge, wires the native overlay
 * "Join Game" callback to the connect operation (FR3.1 path 1), and registers
 * the presence scanner's tick and a Rich Presence clear on client stop.
 * Otherwise publishes a {@code Noop} bridge pair (FR0.2/FR0.3).
 *
 * <p>Registered as the <strong>third</strong> {@code "client"} entrypoint in
 * this module's {@code fabric.mod.json}, after {@code SteamworksClientInitializer}
 * and before {@code FriendsSidebarClientInitializer} (order load-bearing --
 * Decision 4 / Risk 2).
 */
public final class SteamWorldHostingClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SteamworksService steamworksService = SteamworksServiceHandoff.require();
        SteamFriendsGateway gateway = SteamFriendsGatewayHandoff.require();

        Path configFilePath = FabricLoader.getInstance().getConfigDir().resolve("steam-world-hosting.json");
        SteamWorldHostingConfigIO.ParseResult configResult = new SteamWorldHostingConfigIO().load(configFilePath);
        if (configResult.warning() != null) {
            LazuliMod.LOGGER.warn(configResult.warning());
        }
        SteamWorldHostingConfig config = configResult.config();

        boolean active = steamworksService.isSteamAvailable() && config.enabled();
        if (!active) {
            // FR0.2/FR0.3: world hosts as vanilla, no Steam tunnel, no Rich
            // Presence; the reused Friends Sidebar "Join game" slot stays
            // disabled via the Noop bridge pair (never null on require()).
            WorldHostingBridgeHandoff.publish(new NoopWorldJoinRequester(), new NoopFriendHostingStatusReader());
            return;
        }

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            LazuliMod.LOGGER.info("[WorldHosting] Fabric development environment detected -- "
                    + "Mojang session verification will be bypassed for Steam P2P connections on this host "
                    + "(ServerLoginStubDigestMixin/ClientHandshakeStubDigestMixin's debug-only auth bypass).");
        }

        HostingLifecycle lifecycle = new HostingLifecycle(gateway);
        HostGateway hostGateway = new HostGateway(gateway::isDirectFriend);
        HostingPresenceScanner scanner = new HostingPresenceScanner(gateway);

        WorldHostingHookHolder.publish(lifecycle, hostGateway::canJoin);

        WorldJoinRequester joinRequester = SteamAmbientSession.INSTANCE::connectToSteamPeer;
        FriendHostingStatusReader statusReader = scanner;
        WorldHostingBridgeHandoff.publish(joinRequester, statusReader);

        // FR3.1 path 1: Steam's native overlay "Join Game" callback.
        gateway.setJoinRequestedListener((friendSteamId64, connect) -> {
            OptionalLong host = ConnectStringCodec.decode(connect);
            long target = host.isPresent() ? host.getAsLong() : friendSteamId64;
            SteamAmbientSession.INSTANCE.connectToSteamPeer(target);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> scanner.tick());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> lifecycle.stop());
    }
}
