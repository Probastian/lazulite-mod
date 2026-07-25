package de.lazuli.services.steamworks;

import de.lazuli.api.mainmenu.AchievementSummary;

import java.util.List;

/**
 * The shared, {@code services}-layer seam for reading the local player's
 * Steam achievements (batch-2 FR-BB3.2a, batch-2-fixes Item F1). Same
 * one-gateway-per-interface, plain-Java-typed-surface convention as
 * {@link SteamFriendsGateway}; the sole class importing
 * {@code com.codedisaster.steamworks.*} for this capability is
 * {@link SteamworksSteamAchievementsGateway}.
 *
 * <p>
 * Constructed once by a platform composition root
 * ({@code SteamworksClientInitializer}) -- the real
 * {@link SteamworksSteamAchievementsGateway} when
 * {@code SteamworksService.isSteamAvailable()}, otherwise
 * {@link NoopSteamAchievementsGateway}.
 */
public interface SteamAchievementsGateway {

    /**
     * @return every achievement this game defines, in Valve's own enumeration
     *         order; empty if unavailable. Never throws.
     */
    List<AchievementSummary> achievements();
}
