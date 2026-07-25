package de.lazuli.features.mainmenu.achievements;

import java.util.Map;

/**
 * Static name/description mapping for Valve's public Spacewar (App ID 480)
 * sample achievements (batch-3-fixes Item BF5, FR-BF5.1/5.2), keyed by the
 * raw {@code apiName()} {@code AchievementSummary} already exposes. Sourced
 * from Valve's own publicly documented Steamworks SDK sample content for
 * Spacewar (the same sample app this repo's dev/test Steam App ID already
 * points at) -- a well-known, stable set of five achievements. This mapping
 * is intentionally partial/thin (spec Risk #4): any {@code apiName()} not
 * present here falls back to {@code AchievementsPanel}'s existing
 * raw-name-only rendering (FR-BF5.3), never an error/placeholder.
 *
 * <p>Batch-4-fixes Item BF-4-1 populates {@link AchievementMetadata#iconAssetPath()}
 * for every entry with a {@code lazuli:textures/achievements/<apiName>.png}
 * path, resolved from the single shared
 * {@code features/main-menu/src/main/resources/assets/lazuli/textures/achievements/}
 * resource directory (no per-platform duplication). {@code AchievementsPanel}
 * still gracefully falls back to icon-less rendering for any row whose
 * backing PNG isn't actually present on the classpath at render time
 * (FR-BF5.3's fallback allowance, extended by FR-4-1.3 to also cover a
 * missing file behind a non-null {@code iconAssetPath()}).
 */
public final class SpacewarAchievementMapping {

    private SpacewarAchievementMapping() {
    }

    /**
     * @param displayName   human-readable achievement name
     * @param description   human-readable achievement description
     * @param iconAssetPath an {@code assets/lazuli/textures/achievements/<apiName>.png}-shaped
     *                      relative path, or {@code null} if no icon is bundled
     */
    public record AchievementMetadata(String displayName, String description, String iconAssetPath) { }

    public static final Map<String, AchievementMetadata> MAPPING = Map.of(
            "ACH_WIN_ONE_GAME", new AchievementMetadata(
                    "Winner", "Win one game of Spacewar", "lazuli:textures/achievements/ach_win_one_game.png"),
            "ACH_WIN_100_GAMES", new AchievementMetadata(
                    "Champion", "Win 100 games of Spacewar", "lazuli:textures/achievements/ach_win_100_games.png"),
            "ACH_TRAVEL_FAR_ACCUM", new AchievementMetadata(
                    "Interstellar", "Travel 100,000 feet", "lazuli:textures/achievements/ach_travel_far_accum.png"),
            "ACH_TRAVEL_FAR_SINGLE", new AchievementMetadata(
                    "Rocket Man", "Travel 5,000 feet in a single game", "lazuli:textures/achievements/ach_travel_far_single.png"),
            "ACH_SPECIAL_ACHIEVEMENT", new AchievementMetadata(
                    "Sunburned", "Land on the sun", "lazuli:textures/achievements/ach_special_achievement.png")
    );
}
