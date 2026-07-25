package de.lazuli;

import de.lazuli.api.worldhosting.FriendHostingStatusReader;
import de.lazuli.api.worldhosting.HostedWorldStatus;
import de.lazuli.api.worldhosting.WorldInviteSender;
import de.lazuli.api.worldhosting.WorldJoinRequester;
import de.lazuli.features.friendssidebar.api.FriendsSidebarConfig;
import de.lazuli.features.friendssidebar.api.JoinPolicy;
import de.lazuli.features.friendssidebar.config.FriendsSidebarConfigIO;
import de.lazuli.features.worldhosting.api.SteamWorldHostingConfig;
import de.lazuli.features.worldhosting.config.SteamWorldHostingConfigIO;
import de.lazuli.features.worldhosting.services.ConnectStringCodec;
import de.lazuli.features.worldhosting.services.HostGateway;
import de.lazuli.features.worldhosting.services.HostingLifecycle;
import de.lazuli.features.worldhosting.services.HostingPresenceScanner;
import de.lazuli.features.worldhosting.services.JoinGatePolicy;
import de.lazuli.features.worldhosting.services.NoopFriendHostingStatusReader;
import de.lazuli.features.worldhosting.services.NoopWorldInviteSender;
import de.lazuli.features.worldhosting.services.NoopWorldJoinRequester;
import de.lazuli.services.steamworks.SteamFriendsGateway;
import de.lazuli.services.steamworks.SteamworksService;
import de.lazuli.worldhosting.JoinPolicyBridge;
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
            WorldHostingBridgeHandoff.publish(new NoopWorldJoinRequester(), new NoopFriendHostingStatusReader(),
                    new NoopWorldInviteSender());
            return;
        }

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            LazuliMod.LOGGER.info("[WorldHosting] Fabric development environment detected -- "
                    + "Mojang session verification will be bypassed for Steam P2P connections on this host "
                    + "(ServerLoginStubDigestMixin/ClientHandshakeStubDigestMixin's debug-only auth bypass).");
        }

        HostingLifecycle lifecycle = new HostingLifecycle(gateway);
        HostingPresenceScanner scanner = new HostingPresenceScanner(gateway);

        // v1.3 amendment bridge point 1: friends-sidebar.json owns the
        // joinPolicy value; this is a second, independent load of that file
        // (this class runs before FriendsSidebarClientInitializer, so no
        // FriendsSidebarFacade exists yet to read it from).
        Path friendsSidebarConfigPath = FabricLoader.getInstance().getConfigDir().resolve("friends-sidebar.json");
        FriendsSidebarConfigIO.ParseResult friendsSidebarConfigResult =
                new FriendsSidebarConfigIO().load(friendsSidebarConfigPath);
        if (friendsSidebarConfigResult.warning() != null) {
            LazuliMod.LOGGER.warn(friendsSidebarConfigResult.warning());
        }
        JoinPolicy joinPolicy = friendsSidebarConfigResult.config().joinPolicy();
        JoinGatePolicy gatePolicy = JoinPolicyBridge.toGatePolicy(joinPolicy);
        HostGateway hostGateway = HostGateway.forPolicy(gatePolicy, gateway::isDirectFriend);

        WorldHostingHookHolder.publish(lifecycle, hostGateway::canJoin, gatePolicy != JoinGatePolicy.NOBODY);

        WorldJoinRequester joinRequester = SteamAmbientSession.INSTANCE::connectToSteamPeer;
        FriendHostingStatusReader statusReader = scanner;
        WorldInviteSender inviteSender = new WorldInviteSender() {
            @Override
            public boolean isHosting() {
                return lifecycle.currentStatus().hosting() && WorldHostingHookHolder.isAdvertising();
            }

            @Override
            public boolean inviteFriend(long friendSteamId64) {
                HostedWorldStatus status = lifecycle.currentStatus();
                if (!status.hosting()) {
                    return false; // FR-INV5: race guard, never a stale/empty connect string
                }
                return gateway.inviteToGame(friendSteamId64, ConnectStringCodec.encode(status.localSteamId64()));
            }
        };
        WorldHostingBridgeHandoff.publish(joinRequester, statusReader, inviteSender);

        // FR3.1 path 1: Steam's native overlay "Join Game" callback, now
        // routed through the shared SteamJoinRequestDispatcher (Server Join
        // Presence implementation plan, Decision 1) since a second feature
        // (server-join-presence) also needs this single-listener seam.
        // Behavior-preservation note (plan Risk 5): the previous version of
        // this callback unconditionally fell back to connecting to
        // `friendSteamId64` even when `connect` did not decode as this
        // feature's own format -- that permissive fallback is intentionally
        // NOT preserved here (the plan resolves this in favor of tightening,
        // per its Decision 1/Risk 5 reasoning), since preserving it would
        // make this route always report "handled" and silently reintroduce
        // an entrypoint-ordering dependency the dispatcher redesign exists to
        // remove.
        SteamJoinRequestDispatcher.addRoute((friendSteamId64, connect) -> {
            OptionalLong host = ConnectStringCodec.decode(connect);
            if (host.isEmpty()) {
                return false;
            }
            SteamAmbientSession.INSTANCE.connectToSteamPeer(host.getAsLong());
            // batch-3-fixes BF4: friend-initiated joins also record a
            // friend-played-with entry on this route (both join routes are
            // wired into join-history recording, per user direction).
            MainMenuJoinHistoryWriteHandoff.ifPublishedFriendJoin(
                    new de.lazuli.features.mainmenu.config.MainMenuJoinHistoryConfig.FriendJoinEntry(
                            friendSteamId64, System.currentTimeMillis()));
            return true;
        });
        SteamJoinRequestDispatcher.ensureRegisteredWith(gateway);

        ClientTickEvents.END_CLIENT_TICK.register(client -> scanner.tick());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> lifecycle.stop());
    }
}
