package de.lazuli;

import de.lazuli.api.serverbrowser.ServerBrowserSessionFactory;

/**
 * A narrow, composition-root-scoped hand-off publishing the single
 * {@link ServerBrowserSessionFactory} instance {@link ServerBrowserClientInitializer}
 * constructs, so {@link MainMenuClientInitializer} can obtain the same
 * instance rather than constructing a second, competing one -- the exact same
 * shape {@link FriendsSidebarFacadeHandoff}/{@link SteamworksServiceHandoff}
 * already establish (main-menu plan Risk 4 / Sequencing step 9's own first
 * sub-step: no such hand-off existed for this factory before this feature
 * needed one, so this class is new). {@code fabric-1.21.11} (Yarn-mapped)
 * port of the {@code fabric-26.1}/{@code fabric-26.2} class of the same name.
 *
 * <p>Correctness depends only on {@code ServerBrowserClientInitializer}
 * appearing before {@code MainMenuClientInitializer} in this module's
 * {@code fabric.mod.json} {@code "client"} entrypoint array.
 */
public final class ServerBrowserSessionFactoryHandoff {

    private static volatile ServerBrowserSessionFactory instance;

    private ServerBrowserSessionFactoryHandoff() {
    }

    public static void publish(ServerBrowserSessionFactory factory) {
        instance = factory;
    }

    public static ServerBrowserSessionFactory require() {
        ServerBrowserSessionFactory published = instance;
        if (published == null) {
            throw new IllegalStateException(
                    "ServerBrowserSessionFactoryHandoff.require() called before ServerBrowserClientInitializer "
                            + "published a ServerBrowserSessionFactory -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
