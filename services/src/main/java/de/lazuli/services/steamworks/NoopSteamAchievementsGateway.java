package de.lazuli.services.steamworks;

import de.lazuli.api.mainmenu.AchievementSummary;

import java.util.List;

/**
 * A {@link SteamAchievementsGateway} that never touches steamworks4j --
 * constructed whenever {@code SteamworksService.isSteamAvailable()} is
 * {@code false}, mirrors {@link NoopSteamFriendsGateway}.
 */
public final class NoopSteamAchievementsGateway implements SteamAchievementsGateway {

    @Override
    public List<AchievementSummary> achievements() {
        return List.of();
    }

    @Override
    public int statValueInt(String statApiName) {
        return 0;
    }

    @Override
    public float statValueFloat(String statApiName) {
        return 0f;
    }
}
