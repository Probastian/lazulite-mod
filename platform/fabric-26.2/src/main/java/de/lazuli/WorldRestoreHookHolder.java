package de.lazuli;

import de.lazuli.api.cloudsync.WorldRestoreHook;

/**
 * Bridges {@link WorldRestoreHook} from this module's composition root
 * ({@code SteamCloudSyncClientInitializer}) to {@code features/main-menu}'s
 * Worlds-tab rendering code ({@code WorldsPanel}/{@code WorldRestoreScreen}),
 * which is a separate composition root and therefore cannot receive this
 * dependency through a constructor call site. Same narrow,
 * composition-root-scoped static-holder shape as
 * {@link WorldSyncToggleHookHolder}.
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * WorldRestoreHookHolder.publish(coordinator.worldRestoreService());
 * }</pre>
 */
public final class WorldRestoreHookHolder {

    private static volatile WorldRestoreHook instance;

    private WorldRestoreHookHolder() {
    }

    public static void publish(WorldRestoreHook hook) {
        instance = hook;
    }

    /** @return the published hook, or {@code null} if not yet published (e.g. Steam Cloud Sync disabled). */
    public static WorldRestoreHook getOrNull() {
        return instance;
    }
}
