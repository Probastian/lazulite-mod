package de.lazuli.features.steamcloudsync.services;

/**
 * DEV-ONLY TESTING AID -- not a shipped feature.
 *
 * <p>Lets a developer running this mod locally force the next (and every
 * subsequent, while enabled) Steam Cloud upload attempt to fail, so the
 * conflict-detection/resolution flow (see {@code docs/specs/cloud-sync-conflict-ux.md})
 * can be manually tested without physically manipulating two real
 * devices/Steam accounts.
 *
 * <p><b>How to use:</b> add {@code -Dlazuli.debug.forceUploadFailure=true} to
 * the JVM args of your dev run configuration (e.g. the Gradle {@code runClient}
 * task's {@code jvmArgs}, or your IDE run config) and (re)launch. Every
 * upload attempt made by {@link WorldSaveSyncService#syncWorldNow} will then
 * be forced through the same failure path as a genuine Steam Cloud write
 * failure ({@link WorldSyncStatusTracker#markError}), without a real Steam
 * API call ever being made. Set it back to {@code false} (or remove the
 * flag) and relaunch to restore normal behavior.
 *
 * <p>This is intentionally read via {@link System#getProperty(String)} on
 * every call (never cached, never settable from any normal in-game code
 * path) so that: (1) a normal player's game can never accidentally end up
 * with this flag flipped on, since nothing in this codebase ever calls
 * {@link System#setProperty(String, String)} for this key; and (2) a
 * developer can toggle it purely via run-config/JVM-arg edits, with no new
 * UI, keybind, or command needed.
 */
final class DebugSyncFaultInjector {

    private static final String PROPERTY_KEY = "lazuli.debug.forceUploadFailure";

    private DebugSyncFaultInjector() {
    }

    /**
     * @return {@code true} only if the {@code lazuli.debug.forceUploadFailure}
     *         JVM system property is set to {@code "true"} (case-insensitive);
     *         {@code false} in every other case, including when the property
     *         is absent (the default, and the only state a normal player's
     *         game can ever be in).
     */
    static boolean shouldForceUploadFailure() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY_KEY, "false"));
    }
}
