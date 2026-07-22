package de.lazuli;

import de.lazuli.api.richpresence.RichPresenceFacade;
import de.lazuli.api.serverbrowser.ServerBrowserSessionFactory;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.features.mainmenu.config.StoreCatalogConfigIO;
import de.lazuli.features.mainmenu.config.WardrobeConfigIO;
import de.lazuli.features.mainmenu.services.StoreCatalog;
import de.lazuli.mainmenu.MainMenuBackgroundRenderer;
import de.lazuli.mainmenu.MainMenuScreen;
import de.lazuli.mainmenu.MainMenuStoreOwnershipChecker;
import de.lazuli.services.steamworks.SteamworksService;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.nio.file.Path;

/**
 * Client-only composition root for the Main Menu ("Stonebound") feature
 * (specification Public API item 3): the new title screen replacement --
 * {@code fabric-1.21.11} (Yarn-mapped, obfuscated) port of the
 * {@code fabric-26.1}/{@code fabric-26.2} class of the same name.
 *
 * <p>Registered as the <strong>last</strong> {@code "client"} entrypoint in
 * this module's {@code fabric.mod.json}, after {@code ServerBrowserClientInitializer}
 * -- load-bearing, since this obtains {@link FriendsSidebarFacadeHandoff}'s
 * and {@link ServerBrowserSessionFactoryHandoff}'s already-published
 * instances.
 *
 * <p>FR1.2's disconnect/world-exit "return to MainMenuScreen" call site is
 * wired via {@link de.lazuli.mixin.ClientTitleScreenRedirectMixin} (a single
 * choke-point mixin on vanilla's own {@code MinecraftClient.setScreen(Screen)},
 * see that mixin's own Javadoc for the confirmed call-site enumeration) --
 * {@link #buildScreen} below is the same screen-construction logic both the
 * initial-boot path (FR1.1) and that mixin's redirect (FR1.2) share, published
 * via {@link MainMenuScreenFactoryHandoff} since the mixin, being merged
 * directly into vanilla's {@code MinecraftClient} class, has no constructor
 * call site of its own to inject this composition root's dependencies
 * through.
 */
public final class MainMenuClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FriendsSidebarFacade friendsSidebarFacade = FriendsSidebarFacadeHandoff.require();
        ServerBrowserSessionFactory serverBrowserSessionFactory = ServerBrowserSessionFactoryHandoff.require();
        SteamworksService steamworksService = SteamworksServiceHandoff.require();
        RichPresenceFacade richPresenceFacade = RichPresenceFacadeHandoff.require();
        boolean steamAvailable = steamworksService.isSteamAvailable();

        Path configDir = FabricLoader.getInstance().getConfigDir();

        StoreCatalogConfigIO.ParseResult catalogResult = new StoreCatalogConfigIO()
                .load(configDir.resolve("main-menu-store-catalog.json"));
        if (catalogResult.warning() != null) {
            LazuliMod.LOGGER.warn(catalogResult.warning());
        }

        Path wardrobeConfigPath = configDir.resolve("main-menu-wardrobe.json");
        WardrobeConfigIO wardrobeConfigIO = new WardrobeConfigIO();
        WardrobeConfigIO.ParseResult wardrobeResult = wardrobeConfigIO.load(wardrobeConfigPath);
        if (wardrobeResult.warning() != null) {
            LazuliMod.LOGGER.warn(wardrobeResult.warning());
        }

        MainMenuStoreOwnershipChecker ownershipChecker =
                new MainMenuStoreOwnershipChecker(steamAvailable, LazuliMod.LOGGER::warn);
        StoreCatalog storeCatalog = new StoreCatalog(catalogResult.items(), ownershipChecker);

        MainMenuBackgroundRenderer background = new MainMenuBackgroundRenderer();

        java.util.function.Supplier<Screen> screenFactory = () -> buildScreen(background, friendsSidebarFacade,
                serverBrowserSessionFactory, steamAvailable, storeCatalog, ownershipChecker, wardrobeResult,
                wardrobeConfigIO, wardrobeConfigPath, richPresenceFacade);
        MainMenuScreenFactoryHandoff.publish(screenFactory);

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> MinecraftClient.getInstance().setScreen(screenFactory.get()));
    }

    private static Screen buildScreen(MainMenuBackgroundRenderer background, FriendsSidebarFacade friendsSidebarFacade,
                                       ServerBrowserSessionFactory serverBrowserSessionFactory, boolean steamAvailable,
                                       StoreCatalog storeCatalog, MainMenuStoreOwnershipChecker ownershipChecker,
                                       WardrobeConfigIO.ParseResult wardrobeResult, WardrobeConfigIO wardrobeConfigIO,
                                       Path wardrobeConfigPath, RichPresenceFacade richPresenceFacade) {
        return new MainMenuScreen(background, friendsSidebarFacade, serverBrowserSessionFactory,
                steamAvailable, storeCatalog, ownershipChecker, wardrobeResult.config(), richPresenceFacade,
                equipSnapshot -> {
                    try {
                        String serialized = wardrobeConfigIO.serialize(
                                new de.lazuli.features.mainmenu.config.WardrobeConfig(equipSnapshot));
                        java.nio.file.Files.writeString(wardrobeConfigPath, serialized);
                    } catch (java.io.IOException e) {
                        LazuliMod.LOGGER.warn("Failed to persist main-menu-wardrobe config: " + e);
                    }
                });
    }
}
