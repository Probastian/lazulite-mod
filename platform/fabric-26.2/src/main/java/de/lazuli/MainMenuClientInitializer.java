package de.lazuli;

import de.lazuli.api.richpresence.RichPresenceFacade;
import de.lazuli.api.serverbrowser.ServerBrowserSessionFactory;
import de.lazuli.api.serverjoinpresence.FriendServerPresenceReader;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.features.mainmenu.config.MainMenuJoinHistoryConfig;
import de.lazuli.features.mainmenu.config.MainMenuJoinHistoryConfigIO;
import de.lazuli.features.mainmenu.config.StoreCatalogConfigIO;
import de.lazuli.features.mainmenu.config.WardrobeConfigIO;
import de.lazuli.features.mainmenu.services.StoreCatalog;
import de.lazuli.mainmenu.MainMenuBackgroundRenderer;
import de.lazuli.mainmenu.MainMenuScreen;
import de.lazuli.mainmenu.MainMenuStoreOwnershipChecker;
import de.lazuli.services.steamworks.SteamAchievementsGateway;
import de.lazuli.services.steamworks.SteamworksService;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;

/**
 * Client-only composition root for the Main Menu ("Stonebound") feature
 * (specification Public API item 3): the new title screen replacement.
 *
 * <p>Registered as the <strong>last</strong> {@code "client"} entrypoint in
 * this module's {@code fabric.mod.json}, after {@code ServerBrowserClientInitializer}
 * -- load-bearing, since this obtains {@link FriendsSidebarFacadeHandoff}'s
 * and {@link ServerBrowserSessionFactoryHandoff}'s already-published
 * instances (implementation plan Files to Create note on
 * {@code MainMenuClientInitializer}).
 *
 * <p><strong>Scope note for this batch (Sequencing steps 12-13):</strong> the
 * Servers/Store/Wardrobe tabs were fully wired in a prior batch. FR1.2's
 * disconnect/world-exit "return to MainMenuScreen" call sites are now wired
 * too, via {@link de.lazuli.mixin.GuiTitleScreenRedirectMixin} (a single
 * choke-point mixin on vanilla's own {@code Gui.setScreen(Screen)}, see that
 * mixin's own Javadoc for the confirmed call-site enumeration) --
 * {@link #buildScreen()} below is the same screen-construction logic both the
 * initial-boot path (FR1.1) and that mixin's redirect (FR1.2) now share,
 * published via {@link MainMenuScreenFactoryHandoff} since the mixin, being
 * merged directly into vanilla's {@code Gui} class, has no constructor call
 * site of its own to inject this composition root's dependencies through.
 */
public final class MainMenuClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FriendsSidebarFacade friendsSidebarFacade = FriendsSidebarFacadeHandoff.require();
        ServerBrowserSessionFactory serverBrowserSessionFactory = ServerBrowserSessionFactoryHandoff.require();
        SteamworksService steamworksService = SteamworksServiceHandoff.require();
        RichPresenceFacade richPresenceFacade = RichPresenceFacadeHandoff.require();
        // Batch-2 FR-BB4.1: obtained via the same platform-composition-root
        // handoff-broker pattern as every other cross-feature dependency in
        // this class -- ServerJoinPresenceClientInitializer runs before this
        // one (fabric.mod.json entrypoint order), so the real reader (or the
        // Noop fallback if that feature/Steam is unavailable) is already
        // published by the time this line runs.
        FriendServerPresenceReader friendServerPresenceReader = ServerJoinPresenceBridgeHandoff.requirePresenceReader();
        // Batch-2-fixes Item F1: same handoff-broker pattern as every other
        // cross-feature dependency in this class -- SteamworksClientInitializer
        // runs before this one, so the gateway (or Noop fallback) is already
        // published by the time this line runs.
        SteamAchievementsGateway steamAchievementsGateway = SteamAchievementsGatewayHandoff.require();
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

        // batch-3-fixes Item BF4: same fail-closed-with-logged-warning
        // load pattern as the wardrobe/store-catalog configs above.
        Path joinHistoryConfigPath = configDir.resolve("main-menu-join-history.json");
        MainMenuJoinHistoryConfigIO joinHistoryConfigIO = new MainMenuJoinHistoryConfigIO();
        MainMenuJoinHistoryConfigIO.ParseResult joinHistoryResult = joinHistoryConfigIO.load(joinHistoryConfigPath);
        if (joinHistoryResult.warning() != null) {
            LazuliMod.LOGGER.warn(joinHistoryResult.warning());
        }
        // BF4 Decision 6: a mutable holder + synchronized read-modify-write,
        // since both write-side callbacks (server joins, friend joins) can
        // fire independently and must not race/clobber each other's upsert.
        Object joinHistoryLock = new Object();
        java.util.concurrent.atomic.AtomicReference<MainMenuJoinHistoryConfig> joinHistoryHolder =
                new java.util.concurrent.atomic.AtomicReference<>(joinHistoryResult.config());
        MainMenuJoinHistoryWriteHandoff.publish(
                serverEntry -> {
                    synchronized (joinHistoryLock) {
                        MainMenuJoinHistoryConfig updated = joinHistoryHolder.get().upsertServer(serverEntry);
                        joinHistoryHolder.set(updated);
                        String warning = joinHistoryConfigIO.save(joinHistoryConfigPath, updated);
                        if (warning != null) {
                            LazuliMod.LOGGER.warn(warning);
                        }
                    }
                },
                friendEntry -> {
                    synchronized (joinHistoryLock) {
                        MainMenuJoinHistoryConfig updated = joinHistoryHolder.get().upsertFriend(friendEntry);
                        joinHistoryHolder.set(updated);
                        String warning = joinHistoryConfigIO.save(joinHistoryConfigPath, updated);
                        if (warning != null) {
                            LazuliMod.LOGGER.warn(warning);
                        }
                    }
                });

        MainMenuBackgroundRenderer background = new MainMenuBackgroundRenderer();

        java.util.function.Supplier<Screen> screenFactory = () -> buildScreen(background, friendsSidebarFacade,
                serverBrowserSessionFactory, steamAvailable, storeCatalog, ownershipChecker, wardrobeResult,
                wardrobeConfigIO, wardrobeConfigPath, richPresenceFacade, friendServerPresenceReader,
                steamAchievementsGateway, joinHistoryResult.config());
        MainMenuScreenFactoryHandoff.publish(screenFactory);

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> Minecraft.getInstance().setScreenAndShow(screenFactory.get()));
    }

    private static Screen buildScreen(MainMenuBackgroundRenderer background, FriendsSidebarFacade friendsSidebarFacade,
                                       ServerBrowserSessionFactory serverBrowserSessionFactory, boolean steamAvailable,
                                       StoreCatalog storeCatalog, MainMenuStoreOwnershipChecker ownershipChecker,
                                       WardrobeConfigIO.ParseResult wardrobeResult, WardrobeConfigIO wardrobeConfigIO,
                                       Path wardrobeConfigPath, RichPresenceFacade richPresenceFacade,
                                       FriendServerPresenceReader friendServerPresenceReader,
                                       SteamAchievementsGateway steamAchievementsGateway,
                                       MainMenuJoinHistoryConfig joinHistoryConfig) {
        return new MainMenuScreen(background, friendsSidebarFacade, serverBrowserSessionFactory,
                steamAvailable, storeCatalog, ownershipChecker, wardrobeResult.config(), richPresenceFacade,
                friendServerPresenceReader, steamAchievementsGateway, joinHistoryConfig,
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
