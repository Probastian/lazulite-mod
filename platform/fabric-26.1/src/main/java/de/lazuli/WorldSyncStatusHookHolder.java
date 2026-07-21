package de.lazuli;

import de.lazuli.api.cloudsync.WorldSyncStatusHook;

/**
 * Bridges {@link WorldSyncStatusHook} to the per-world sync-status icon's
 * {@code @Mixin} (Pattern 3, {@code ui-guidelines.md}), which is injected
 * directly into the real vanilla world-row class and therefore has no
 * constructor vanilla code will ever call with our own dependencies. Same
 * narrow, composition-root-scoped static-holder shape as
 * {@link WorldSyncToggleHookHolder} -- published once by
 * {@code SteamCloudSyncClientInitializer} at startup, read by the mixin's
 * injected render handler.
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * WorldSyncStatusHookHolder.publish(coordinator.worldSyncStatusTracker());
 * }</pre>
 */
public final class WorldSyncStatusHookHolder {

    private static volatile WorldSyncStatusHook instance;

    private WorldSyncStatusHookHolder() {
    }

    public static void publish(WorldSyncStatusHook hook) {
        instance = hook;
    }

    /** @return the published hook, or {@code null} if not yet published (e.g. Steam Cloud Sync disabled). */
    public static WorldSyncStatusHook getOrNull() {
        return instance;
    }
}
