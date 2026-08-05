package de.lazuli;

import de.lazuli.api.cloudsync.WorldFreshnessHook;

/**
 * Bridges {@link WorldFreshnessHook} to {@code WorldsPanel}'s FR-Q.2
 * per-world freshness indicator. Same narrow, composition-root-scoped
 * static-holder shape as {@link WorldSyncStatusHookHolder} -- published once
 * by {@code SteamCloudSyncClientInitializer} at startup, read by
 * {@code WorldsPanel}.
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * WorldFreshnessHookHolder.publish(coordinator.worldSaveSyncService());
 * }</pre>
 */
public final class WorldFreshnessHookHolder {

    private static volatile WorldFreshnessHook instance;

    private WorldFreshnessHookHolder() {
    }

    public static void publish(WorldFreshnessHook hook) {
        instance = hook;
    }

    /** @return the published hook, or {@code null} if not yet published (e.g. Steam Cloud Sync disabled). */
    public static WorldFreshnessHook getOrNull() {
        return instance;
    }
}
