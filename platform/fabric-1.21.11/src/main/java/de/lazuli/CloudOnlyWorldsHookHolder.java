package de.lazuli;

import de.lazuli.api.cloudsync.CloudOnlyWorldsHook;

/**
 * Bridges {@link CloudOnlyWorldsHook} from this module's composition root
 * ({@code SteamCloudSyncClientInitializer}) to {@code features/main-menu}'s
 * Worlds-tab rendering code ({@code WorldsPanel}), which is a separate
 * composition root and therefore cannot receive this dependency through a
 * constructor call site. Same narrow, composition-root-scoped static-holder
 * shape as {@link WorldSyncToggleHookHolder}.
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * CloudOnlyWorldsHookHolder.publish(coordinator.cloudOnlyWorldsFacade());
 * }</pre>
 */
public final class CloudOnlyWorldsHookHolder {

    private static volatile CloudOnlyWorldsHook instance;

    private CloudOnlyWorldsHookHolder() {
    }

    public static void publish(CloudOnlyWorldsHook hook) {
        instance = hook;
    }

    /** @return the published hook, or {@code null} if not yet published (e.g. Steam Cloud Sync disabled). */
    public static CloudOnlyWorldsHook getOrNull() {
        return instance;
    }
}
