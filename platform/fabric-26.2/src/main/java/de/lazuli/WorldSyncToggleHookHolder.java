package de.lazuli;

import de.lazuli.api.cloudsync.WorldSyncToggleHook;

/**
 * Bridges {@link WorldSyncToggleHook} to the per-world sync-toggle icon's
 * {@code @Mixin} (Pattern 3, {@code ui-guidelines.md}), which is injected
 * directly into the real vanilla world-row class and therefore has no
 * constructor vanilla code will ever call with our own dependencies. Same
 * narrow, composition-root-scoped static-holder shape as
 * {@link SteamworksServiceHandoff} -- published once by
 * {@code SteamCloudSyncClientInitializer} at startup, read by the mixin's
 * injected render/click handlers.
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * WorldSyncToggleHookHolder.publish(coordinator.worldSyncPreferenceService());
 * }</pre>
 */
public final class WorldSyncToggleHookHolder {

    private static volatile WorldSyncToggleHook instance;

    private WorldSyncToggleHookHolder() {
    }

    public static void publish(WorldSyncToggleHook hook) {
        instance = hook;
    }

    /** @return the published hook, or {@code null} if not yet published (e.g. Steam Cloud Sync disabled). */
    public static WorldSyncToggleHook getOrNull() {
        return instance;
    }
}
