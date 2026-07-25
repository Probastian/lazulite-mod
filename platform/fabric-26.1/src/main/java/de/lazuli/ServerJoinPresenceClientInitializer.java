package de.lazuli;

import de.lazuli.api.serverjoinpresence.FriendServerPresenceReader;
import de.lazuli.api.serverjoinpresence.ServerJoinRequester;
import de.lazuli.features.serverjoinpresence.api.ServerJoinPresenceConfig;
import de.lazuli.features.serverjoinpresence.config.ServerJoinPresenceConfigIO;
import de.lazuli.features.serverjoinpresence.services.NoopFriendServerPresenceReader;
import de.lazuli.features.serverjoinpresence.services.NoopServerJoinRequester;
import de.lazuli.features.serverjoinpresence.services.ServerConnectStringCodec;
import de.lazuli.features.serverjoinpresence.services.ServerPresenceScanner;
import de.lazuli.features.serverjoinpresence.services.ServerSessionLifecycle;
import de.lazuli.serverjoinpresence.ServerJoinOperation;
import de.lazuli.services.steamworks.SteamFriendsGateway;
import de.lazuli.services.steamworks.SteamworksService;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ServerData;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Client-only composition root for the Server Join Presence feature on this
 * platform module. Obtains the shared {@link SteamworksService}/
 * {@link SteamFriendsGateway} via their hand-offs (never re-initializes
 * Steamworks), loads this feature's config, and -- when Steam is available
 * and the feature is enabled -- constructs the session lifecycle/presence
 * scanner, registers this feature's route with
 * {@link SteamJoinRequestDispatcher} (Decision 1, order-independent relative
 * to {@code SteamWorldHostingClientInitializer}), hooks
 * {@link ClientPlayConnectionEvents#JOIN}/{@link ClientPlayConnectionEvents#DISCONNECT}
 * to advertise/clear Rich Presence {@code "connect"} for real multiplayer
 * sessions only (singleplayer/integrated-server sessions remain
 * {@code HostingLifecycle}'s own responsibility), and registers the presence
 * scanner's tick. Otherwise publishes a {@code Noop} bridge pair
 * (FR0.2/FR0.3).
 *
 * <p>Must run after {@code SteamworksClientInitializer} (needs both
 * hand-offs); relative order to every other feature initializer -- including
 * {@code SteamWorldHostingClientInitializer} -- is not load-bearing (Decision
 * 1 makes the shared join-request dispatcher registration order-independent).
 */
public final class ServerJoinPresenceClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SteamworksService steamworksService = SteamworksServiceHandoff.require();
        SteamFriendsGateway gateway = SteamFriendsGatewayHandoff.require();

        Path configFilePath = FabricLoader.getInstance().getConfigDir().resolve("server-join-presence.json");
        ServerJoinPresenceConfigIO.ParseResult configResult = new ServerJoinPresenceConfigIO().load(configFilePath);
        if (configResult.warning() != null) {
            LazuliMod.LOGGER.warn(configResult.warning());
        }
        ServerJoinPresenceConfig config = configResult.config();

        boolean active = steamworksService.isSteamAvailable() && config.enabled();
        if (!active) {
            // FR0.2/FR0.3: no Rich Presence "connect" ownership, no friend scan;
            // the bridge pair is always Noop so a future consumer's require()
            // never returns null.
            ServerJoinPresenceBridgeHandoff.publish(new NoopServerJoinRequester(), new NoopFriendServerPresenceReader());
            return;
        }

        ServerSessionLifecycle lifecycle = new ServerSessionLifecycle(gateway);
        ServerPresenceScanner scanner = new ServerPresenceScanner(gateway);

        ServerJoinRequester joinRequester = ServerJoinOperation.INSTANCE::connectToServer;
        FriendServerPresenceReader presenceReader = scanner;
        ServerJoinPresenceBridgeHandoff.publish(joinRequester, presenceReader);

        // Decision 1: route this feature's own connect-string format through
        // the shared dispatcher instead of calling
        // gateway.setJoinRequestedListener directly -- steam-world-hosting
        // registers its own route the same way (see its edited initializer).
        SteamJoinRequestDispatcher.addRoute((friendSteamId64, connect) -> {
            Optional<ServerConnectStringCodec.HostPort> target = ServerConnectStringCodec.decode(connect);
            if (target.isEmpty()) {
                return false;
            }
            ServerJoinOperation.INSTANCE.connectToServer(target.get().host(), target.get().port());
            // batch-3-fixes BF4/FR-BF4.3: record a friend-played-with entry for
            // this feature's own server-join-presence route (the route whose
            // decoded target is an actual joinable host:port).
            MainMenuJoinHistoryWriteHandoff.ifPublishedFriendJoin(
                    new de.lazuli.features.mainmenu.config.MainMenuJoinHistoryConfig.FriendJoinEntry(
                            friendSteamId64, System.currentTimeMillis()));
            return true;
        });
        SteamJoinRequestDispatcher.ensureRegisteredWith(gateway);

        // FR1.1/FR1.2: advertise/clear "connect" only for a real multiplayer
        // session -- never for the local integrated server (HostingLifecycle's
        // own responsibility). advertising[] tracks whether *this* feature is
        // the current "connect" owner, so DISCONNECT never races/clobbers a
        // singleplayer session HostingLifecycle just started (FR1.2/FR1.3).
        boolean[] advertising = { false };
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.hasSingleplayerServer()) {
                return;
            }
            ServerData server = client.getCurrentServer();
            if (server == null || server.ip == null || server.ip.isBlank()) {
                return;
            }
            HostPortSplit split = HostPortSplit.parse(server.ip);
            lifecycle.onJoinedRemoteServer(split.host(), split.port());
            advertising[0] = true;
            // batch-3-fixes BF4/FR-BF4.2: record this real server-join
            // transition for HomePanel's Recent section.
            String serverName = server.name != null ? server.name : server.ip;
            MainMenuJoinHistoryWriteHandoff.ifPublishedServerJoin(
                    new de.lazuli.features.mainmenu.config.MainMenuJoinHistoryConfig.ServerJoinEntry(
                            server.ip, serverName, System.currentTimeMillis()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (advertising[0]) {
                lifecycle.onLeftServer();
                advertising[0] = false;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> scanner.tick());
    }

    /**
     * Minimal local {@code "host:port"} splitter for a raw
     * {@code ServerData.ip} address string -- kept local rather than reusing
     * {@code ServerAddress} accessors to avoid depending on an
     * unconfirmed-this-pass accessor name (implementation plan Risk 2/3).
     */
    private record HostPortSplit(String host, int port) {
        static HostPortSplit parse(String raw) {
            int lastColon = raw.lastIndexOf(':');
            if (lastColon <= 0 || lastColon == raw.length() - 1) {
                return new HostPortSplit(raw, ServerConnectStringCodec.DEFAULT_PORT);
            }
            try {
                int port = Integer.parseInt(raw.substring(lastColon + 1));
                return new HostPortSplit(raw.substring(0, lastColon), port);
            } catch (NumberFormatException e) {
                return new HostPortSplit(raw, ServerConnectStringCodec.DEFAULT_PORT);
            }
        }
    }
}
