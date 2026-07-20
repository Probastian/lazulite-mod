package de.lazuli;

import de.lazuli.services.steamworks.SteamFriendsGateway;

/**
 * Narrow, composition-root-scoped hand-off publishing the single shared
 * {@link SteamFriendsGateway} that {@link SteamworksClientInitializer}
 * constructs, so later client entrypoints in this module
 * ({@code SteamWorldHostingClientInitializer}, {@code FriendsSidebarClientInitializer})
 * consume the same instance instead of each constructing their own
 * {@code SteamFriends}. Same publish/require, {@code volatile}-static shape as
 * {@link SteamworksServiceHandoff}; correctness depends only on
 * {@code SteamworksClientInitializer} appearing first in this module's
 * {@code fabric.mod.json} {@code "client"} array (it already does).
 */
public final class SteamFriendsGatewayHandoff {

    private static volatile SteamFriendsGateway instance;

    private SteamFriendsGatewayHandoff() {
    }

    /** Publishes {@code gateway}; called once by {@link SteamworksClientInitializer}. */
    public static void publish(SteamFriendsGateway gateway) {
        instance = gateway;
    }

    /**
     * @return the previously-published gateway
     * @throws IllegalStateException if called before {@link #publish} -- check
     *                                this module's {@code fabric.mod.json}
     *                                {@code "client"} entrypoint order
     */
    public static SteamFriendsGateway require() {
        SteamFriendsGateway published = instance;
        if (published == null) {
            throw new IllegalStateException(
                    "SteamFriendsGatewayHandoff.require() called before SteamworksClientInitializer published a "
                            + "SteamFriendsGateway -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
