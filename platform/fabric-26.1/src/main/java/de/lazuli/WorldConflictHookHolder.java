package de.lazuli;

import de.lazuli.api.cloudsync.WorldConflictHook;
import de.lazuli.api.cloudsync.WorldConflictResolutionHook;

/**
 * Bridges {@link WorldConflictHook}/{@link WorldConflictResolutionHook} to
 * {@code WorldsPanel}'s FR-V conflict indicator/{@code WorldConflictScreen}.
 * Same narrow, composition-root-scoped static-holder shape as
 * {@link WorldFreshnessHookHolder}/{@link WorldRestoreHookHolder} -- published
 * once by {@code SteamCloudSyncClientInitializer} at startup, read by
 * {@code WorldsPanel}/{@code WorldConflictScreen}.
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * WorldConflictHookHolder.publish(coordinator.worldSaveSyncService());
 * }</pre>
 */
public final class WorldConflictHookHolder {

    private static volatile WorldConflictHook conflictHook;
    private static volatile WorldConflictResolutionHook resolutionHook;

    private WorldConflictHookHolder() {
    }

    /**
     * @param hook the single {@code WorldSaveSyncService} instance,
     *             implementing both {@link WorldConflictHook} and
     *             {@link WorldConflictResolutionHook}
     */
    public static <T extends WorldConflictHook & WorldConflictResolutionHook> void publish(T hook) {
        conflictHook = hook;
        resolutionHook = hook;
    }

    /** @return the published conflict-classification hook, or {@code null} if not yet published. */
    public static WorldConflictHook getOrNull() {
        return conflictHook;
    }

    /** @return the published conflict-resolution hook, or {@code null} if not yet published. */
    public static WorldConflictResolutionHook getResolutionHookOrNull() {
        return resolutionHook;
    }
}
