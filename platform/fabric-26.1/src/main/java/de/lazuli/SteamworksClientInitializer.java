package de.lazuli;

import de.lazuli.services.steamworks.SteamAppIdResolver;
import de.lazuli.services.steamworks.SteamworksService;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Client-only composition root for the Steamworks bootstrap service on this
 * platform module.
 *
 * <p>Resolves the App ID (via {@link SteamAppIdResolver}) and the
 * native-library extraction directory (via {@link FabricLoader}'s config
 * directory, kept out of {@code services} so that module stays buildable
 * with no Fabric Loader dependency), constructs the shared
 * {@link SteamworksService}, registers it to pump Steam's callback queue
 * once per client tick and to shut down cleanly on client stop, and logs the
 * resolved availability once. Registered as a second {@code "client"}
 * entrypoint in this module's {@code fabric.mod.json}, alongside the
 * existing {@code HelloWorldMainMenuClientInitializer}.
 *
 * <p>Per {@code docs/adr/0002-platform-composition-root-may-construct-services-classes.md},
 * a platform module's composition root is permitted to construct a concrete
 * {@code services}-layer class purely for bootstrap wiring, generalizing the
 * same exception ADR-0001 already grants for Feature classes.
 *
 * <p>Steam's client-facing API is inherently client-only (it talks to the
 * locally-running desktop Steam client), so this is wired exclusively from
 * this module's {@code "client"} entrypoint, never from the shared
 * {@code "main"} entrypoint ({@link LazuliMod}, also loaded on dedicated
 * servers).
 *
 * <p>Usage example (this is how Fabric Loader invokes it; not normally
 * called directly):
 * <pre>{@code
 * new SteamworksClientInitializer().onInitializeClient();
 * }</pre>
 */
public final class SteamworksClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        long appId = SteamAppIdResolver.resolve(System::getProperty);
        Path nativeLibraryDirectory = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("lazuli")
                .resolve("steamworks-natives");

        SteamworksService steamworksService =
                SteamworksService.create(appId, nativeLibraryDirectory, LazuliMod.LOGGER::warn);

        ClientTickEvents.END_CLIENT_TICK.register(client -> steamworksService.pumpCallbacks());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> steamworksService.shutdown());

        LazuliMod.LOGGER.info("Steamworks bootstrap: isSteamAvailable={}, steamAppId={}",
                steamworksService.isSteamAvailable(), steamworksService.steamAppId());
    }
}
