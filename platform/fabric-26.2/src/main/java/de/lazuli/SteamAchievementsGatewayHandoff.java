package de.lazuli;

import de.lazuli.services.steamworks.SteamAchievementsGateway;

/**
 * Narrow, composition-root-scoped hand-off publishing the single shared
 * {@link SteamAchievementsGateway} that {@link SteamworksClientInitializer}
 * constructs, so {@code MainMenuClientInitializer} consumes the same
 * instance. Same publish/require, {@code volatile}-static shape as
 * {@link SteamFriendsGatewayHandoff}; correctness depends only on
 * {@code SteamworksClientInitializer} appearing before
 * {@code MainMenuClientInitializer} in this module's {@code fabric.mod.json}
 * {@code "client"} array (it already does).
 */
public final class SteamAchievementsGatewayHandoff {

    private static volatile SteamAchievementsGateway instance;

    private SteamAchievementsGatewayHandoff() {
    }

    /** Publishes {@code gateway}; called once by {@link SteamworksClientInitializer}. */
    public static void publish(SteamAchievementsGateway gateway) {
        instance = gateway;
    }

    /**
     * @return the previously-published gateway
     * @throws IllegalStateException if called before {@link #publish} -- check
     *                                this module's {@code fabric.mod.json}
     *                                {@code "client"} entrypoint order
     */
    public static SteamAchievementsGateway require() {
        SteamAchievementsGateway published = instance;
        if (published == null) {
            throw new IllegalStateException(
                    "SteamAchievementsGatewayHandoff.require() called before SteamworksClientInitializer published a "
                            + "SteamAchievementsGateway -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
