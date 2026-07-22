package de.lazuli;

import de.lazuli.features.friendssidebar.api.FriendsSidebarConfig;
import de.lazuli.features.friendssidebar.api.JoinPolicy;
import de.lazuli.features.friendssidebar.config.FriendsSidebarConfigIO;
import de.lazuli.features.friendssidebar.services.FriendsDataSource;
import de.lazuli.features.friendssidebar.services.FriendsService;
import de.lazuli.features.friendssidebar.services.FriendSidebarStateMachine;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.features.friendssidebar.services.NoopFriendsService;
import de.lazuli.api.richpresence.RichPresenceFacade;
import de.lazuli.api.worldhosting.FriendHostingStatusReader;
import de.lazuli.api.worldhosting.WorldInviteSender;
import de.lazuli.api.worldhosting.WorldJoinRequester;
import de.lazuli.features.worldhosting.services.HostGateway;
import de.lazuli.features.worldhosting.services.JoinGatePolicy;
import de.lazuli.friends.FabricFriendsSidebarInjector;
import de.lazuli.services.steamworks.SteamFriendsGateway;
import de.lazuli.services.steamworks.SteamworksService;
import de.lazuli.services.ui.ToastService;
import de.lazuli.worldhosting.JoinPolicyBridge;
import de.lazuli.worldhosting.WorldHostingHookHolder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Client-only composition root for the Friends Sidebar feature on this
 * platform module.
 *
 * <p>Obtains the already-constructed {@link SteamworksService} via
 * {@link SteamworksServiceHandoff#require()} (never re-initializes
 * Steamworks), loads this feature's own config, constructs
 * {@link FriendsService}/{@link NoopFriendsService} depending on
 * availability (FR0.2/FR0.3), registers {@code ClientTickEvents.END_CLIENT_TICK},
 * and constructs {@link FabricFriendsSidebarInjector}.
 *
 * <p>Registered as the <strong>fourth</strong> {@code "client"} entrypoint in
 * this module's {@code fabric.mod.json}, after
 * {@code SteamworksClientInitializer} (order load-bearing -- see
 * {@link SteamworksServiceHandoff}).
 */
public final class FriendsSidebarClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SteamworksService steamworksService = SteamworksServiceHandoff.require();
        SteamFriendsGateway gateway = SteamFriendsGatewayHandoff.require();

        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFilePath = configDir.resolve("friends-sidebar.json");

        FriendsSidebarConfigIO.ParseResult configResult = new FriendsSidebarConfigIO().load(configFilePath);
        if (configResult.warning() != null) {
            LazuliMod.LOGGER.warn(configResult.warning());
        }
        FriendsSidebarConfig config = configResult.config();

        // Cross-feature bridge (Decision 4 / specification-invite-to-game.md
        // D5): Steam World Hosting publishes these before this initializer
        // runs (entrypoint order load-bearing).
        WorldInviteSender worldInviteSender = WorldHostingBridgeHandoff.requireWorldInviteSender();
        ToastService toastService = ToastServiceHandoff.require();
        RichPresenceFacade richPresenceFacade = RichPresenceFacadeHandoff.require();

        FriendsDataSource dataSource = (steamworksService.isSteamAvailable() && config.enabled())
                ? new FriendsService(gateway, config, LazuliMod.LOGGER::warn, worldInviteSender)
                : new NoopFriendsService();

        // v1.3 amendment bridge point 2: dropdown click -> persist -> re-publish
        // (FR7.11). Persists unconditionally; only re-publishes the live
        // predicate if Steam World Hosting itself is enabled.
        Consumer<JoinPolicy> onJoinPolicyChanged = newPolicy -> {
            FriendsSidebarConfig updated = new FriendsSidebarConfig(config.enabled(), config.refreshIntervalSeconds(), newPolicy);
            try {
                new FriendsSidebarConfigIO().save(configFilePath, updated);
            } catch (IOException | RuntimeException e) {
                LazuliMod.LOGGER.warn("Failed to persist friends-sidebar.json: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (WorldHostingHookHolder.isEnabled()) {
                JoinGatePolicy gatePolicy = JoinPolicyBridge.toGatePolicy(newPolicy);
                HostGateway hostGateway = HostGateway.forPolicy(gatePolicy, gateway::isDirectFriend);
                WorldHostingHookHolder.updateJoinPolicy(hostGateway::canJoin, gatePolicy != JoinGatePolicy.NOBODY);
            }
        };

        FriendsSidebarFacade facade = new FriendsSidebarFacade(dataSource, new FriendSidebarStateMachine(),
                config.joinPolicy(), onJoinPolicyChanged);
        facade.setEnabled(config.enabled());
        facade.setSteamAvailable(steamworksService.isSteamAvailable());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            dataSource.tick();
            facade.refresh();
        });

        // Cross-feature bridge (Decision 4): Steam World Hosting publishes
        // these before this initializer runs (entrypoint order load-bearing).
        WorldJoinRequester worldJoinRequester = WorldHostingBridgeHandoff.requireJoinRequester();
        FriendHostingStatusReader hostingStatusReader = WorldHostingBridgeHandoff.requireHostingStatusReader();

        new FabricFriendsSidebarInjector(facade, worldJoinRequester, hostingStatusReader, worldInviteSender,
                toastService, richPresenceFacade);
    }
}
