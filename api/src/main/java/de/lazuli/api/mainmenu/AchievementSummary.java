package de.lazuli.api.mainmenu;

/**
 * One Steam achievement entry for the Achievements tab (batch-2 FR-BB3.4,
 * batch-2-fixes Item F1).
 *
 * <p><strong>Data-availability note (FR-F1.1's {@code javap} check,
 * batch-2-fixes report):</strong> the resolved {@code steamworks4j} fork jar
 * (coordinate {@code com.github.Probastian.steamworks4j:v1.10.0-inventory.1})
 * wraps {@code SteamUserStats.getNumAchievements()}/{@code getAchievementName(int)}/
 * {@code isAchieved(String, boolean)} only -- there is <strong>no</strong>
 * {@code GetAchievementDisplayAttribute} (localized display name/description),
 * no {@code GetAchievementIcon}, no {@code GetAchievementAndUnlockTime}, and no
 * progress-limits binding. {@link #apiName()} is therefore Valve's raw,
 * unlocalized achievement API name (e.g. {@code "ACH_WIN_GAME"}), not a
 * human-readable display name -- there is no separate {@code displayName}/
 * {@code description}/icon field because no such data is obtainable from this
 * binding. {@link #unlocked()} is real (from {@code isAchieved}); there is no
 * unlock timestamp or progress value for the same reason.
 *
 * @param apiName  Valve's raw achievement API name (unlocalized)
 * @param unlocked whether the local player has unlocked this achievement
 */
public record AchievementSummary(String apiName, boolean unlocked) {
}
