package de.lazuli.api.cloudsync;

/**
 * Single, non-duplicated, Minecraft-free presentation-layer helper (mirroring
 * {@link DownloadProgressPresenter}'s existing convention for this feature)
 * that maps a raw, technical {@code RestoreProgressListener.onFailed} reason
 * string -- as produced by {@code WorldRestoreService.beginRestore}/{@code
 * extractAndFinish} -- to a short, friendly, player-facing message.
 *
 * <p>The raw reason strings are implementation detail (local folder paths,
 * archive file names, raw exception messages) never meant for an end player;
 * this helper is the one place that decides what a player actually sees, so
 * both display call sites ({@code WorldRestoreScreen}'s failure text and
 * {@code WorldsPanel}'s transient download-only status message, across all
 * three platforms) stay in sync. Callers should keep logging the original,
 * untranslated {@code reason} string for diagnosis -- only the on-screen text
 * should go through this method.
 */
public final class RestoreFailureMessages {

    private RestoreFailureMessages() {
    }

    /**
     * @param reason the raw, technical reason passed to {@code onFailed}
     * @return a short, actionable, player-facing message
     */
    public static String toPlayerMessage(String reason) {
        if (reason != null) {
            if (reason.contains("already exists")) {
                return "A world with this name already exists locally. Rename or remove it, then try again.";
            }
            if (reason.contains("was not found on Steam Cloud")) {
                return "This world's Cloud backup couldn't be found. It may have been removed.";
            }
        }
        return "The download couldn't be completed. Please try again.";
    }
}
