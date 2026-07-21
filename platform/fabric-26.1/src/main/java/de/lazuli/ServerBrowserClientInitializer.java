package de.lazuli;

import de.lazuli.api.serverbrowser.ServerBrowserSessionFactory;
import de.lazuli.features.serverbrowser.services.ServerBrowserSessionFactoryImpl;
import de.lazuli.serverbrowser.FabricServerBrowserButtonInjector;
import de.lazuli.services.steamworks.SteamworksService;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client-only composition root for the Server Browser feature on this
 * platform module.
 *
 * <p>Obtains the already-constructed {@link SteamworksService} via
 * {@link SteamworksServiceHandoff#require()} (never re-initializes
 * Steamworks, never constructs a second App-ID resolution path, spec
 * Architecture), constructs {@link ServerBrowserSessionFactoryImpl}, and
 * registers {@link FabricServerBrowserButtonInjector} (Pattern 1).
 *
 * <p>Registered in this module's {@code fabric.mod.json} {@code "client"}
 * array after {@code "de.lazuli.SteamworksClientInitializer"} (order
 * load-bearing -- see {@link SteamworksServiceHandoff}); position relative to
 * the other feature initializers is not load-bearing (no shared state).
 */
public final class ServerBrowserClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SteamworksService steamworksService = SteamworksServiceHandoff.require();

        ServerBrowserSessionFactory sessionFactory = new ServerBrowserSessionFactoryImpl(
                steamworksService,
                () -> (int) steamworksService.steamAppId(),
                LazuliMod.LOGGER::warn);

        new FabricServerBrowserButtonInjector(sessionFactory, steamworksService);
    }
}
