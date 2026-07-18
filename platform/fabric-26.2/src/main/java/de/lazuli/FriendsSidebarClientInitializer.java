package de.lazuli;

import de.lazuli.features.friendssidebar.api.FriendsSidebarConfig;
import de.lazuli.features.friendssidebar.config.FriendsSidebarConfigIO;
import de.lazuli.features.friendssidebar.services.FriendsDataSource;
import de.lazuli.features.friendssidebar.services.FriendsService;
import de.lazuli.features.friendssidebar.services.FriendSidebarStateMachine;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.features.friendssidebar.services.NoopFriendsService;
import de.lazuli.friends.FabricFriendsSidebarInjector;
import de.lazuli.services.steamworks.SteamworksService;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

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

        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFilePath = configDir.resolve("friends-sidebar.json");

        FriendsSidebarConfigIO.ParseResult configResult = new FriendsSidebarConfigIO().load(configFilePath);
        if (configResult.warning() != null) {
            LazuliMod.LOGGER.warn(configResult.warning());
        }
        FriendsSidebarConfig config = configResult.config();

        FriendsDataSource dataSource = (steamworksService.isSteamAvailable() && config.enabled())
                ? new FriendsService(config, LazuliMod.LOGGER::warn)
                : new NoopFriendsService();

        FriendsSidebarFacade facade = new FriendsSidebarFacade(dataSource, new FriendSidebarStateMachine());
        facade.setEnabled(steamworksService.isSteamAvailable() && config.enabled());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            dataSource.tick();
            facade.refresh();
        });

        new FabricFriendsSidebarInjector(facade);
    }
}
