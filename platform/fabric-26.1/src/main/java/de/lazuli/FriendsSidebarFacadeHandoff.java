package de.lazuli;

import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;

/**
 * A narrow, composition-root-scoped hand-off publishing the single
 * {@link FriendsSidebarFacade} instance {@link FriendsSidebarClientInitializer}
 * constructs, so {@link MainMenuClientInitializer} can obtain the same
 * instance rather than constructing a second, competing one -- the exact same
 * shape {@link SteamworksServiceHandoff} already establishes (Risk 4 /
 * Sequencing step 8's own first sub-step: no such hand-off existed for this
 * facade before this feature needed one, so this class is new).
 *
 * <p>Correctness depends only on {@code FriendsSidebarClientInitializer}
 * appearing before {@code MainMenuClientInitializer} in this module's
 * {@code fabric.mod.json} {@code "client"} entrypoint array.
 */
public final class FriendsSidebarFacadeHandoff {

    private static volatile FriendsSidebarFacade instance;

    private FriendsSidebarFacadeHandoff() {
    }

    public static void publish(FriendsSidebarFacade facade) {
        instance = facade;
    }

    public static FriendsSidebarFacade require() {
        FriendsSidebarFacade published = instance;
        if (published == null) {
            throw new IllegalStateException(
                    "FriendsSidebarFacadeHandoff.require() called before FriendsSidebarClientInitializer "
                            + "published a FriendsSidebarFacade -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
