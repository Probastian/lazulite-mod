package de.lazuli.services.steamworks;

import com.codedisaster.steamworks.SteamUserStats;
import com.codedisaster.steamworks.SteamUserStatsCallback;

import de.lazuli.api.mainmenu.AchievementSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The real, steamworks4j-backed {@link SteamAchievementsGateway}.
 *
 * <p><strong>Confirmed binding shape (FR-F1.1's mandatory {@code javap}
 * check, batch-2-fixes report):</strong> {@code javap -p} against the
 * resolved fork jar ({@code com.github.Probastian.steamworks4j:v1.10.0-inventory.1})
 * confirms {@code SteamUserStats}/{@code SteamUserStatsNative} exist and wrap
 * {@code getNumAchievements()}, {@code getAchievementName(int)}, and
 * {@code isAchieved(String, boolean)} -- <strong>not</strong> present:
 * {@code RequestCurrentStats} (stats/achievements are available immediately
 * once {@link SteamUserStats} is constructed, consistent with steamworks4j's
 * own usual auto-request-on-construct behavior -- no explicit call needed/
 * available), {@code GetAchievementDisplayAttribute} (no localized name/
 * description), {@code GetAchievementIcon}, {@code GetAchievementAndUnlockTime}
 * (no unlock timestamp), or any progress-limits accessor. This gateway's
 * {@link #achievements()} therefore returns Valve's raw achievement API names
 * and real unlocked/locked status only -- see {@link AchievementSummary}'s own
 * Javadoc for the full data-availability note.
 */
public final class SteamworksSteamAchievementsGateway implements SteamAchievementsGateway {

    private final Consumer<String> warnLogger;
    private final SteamUserStats steamUserStats;

    /**
     * @param warnLogger sink for non-fatal warnings (never throws); may be
     *                   {@code null}
     */
    public SteamworksSteamAchievementsGateway(Consumer<String> warnLogger) {
        this.warnLogger = warnLogger;
        this.steamUserStats = new SteamUserStats(new Callback());
    }

    private void warn(String message) {
        if (warnLogger != null) {
            warnLogger.accept(message);
        }
    }

    @Override
    public List<AchievementSummary> achievements() {
        try {
            int count = steamUserStats.getNumAchievements();
            List<AchievementSummary> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String apiName = steamUserStats.getAchievementName(i);
                if (apiName == null || apiName.isEmpty()) {
                    continue;
                }
                boolean unlocked = steamUserStats.isAchieved(apiName, false);
                result.add(new AchievementSummary(apiName, unlocked));
            }
            return result;
        } catch (RuntimeException e) {
            warn("Failed to read Steam achievements: " + e.getMessage());
            return List.of();
        }
    }

    /** Required by {@link SteamUserStats}'s constructor; no events are consumed. */
    private static final class Callback implements SteamUserStatsCallback {
    }
}
