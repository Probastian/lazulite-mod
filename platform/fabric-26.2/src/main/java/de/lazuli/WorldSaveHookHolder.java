package de.lazuli;

import de.lazuli.features.steamcloudsync.services.WorldSaveSyncService;

import java.nio.file.Path;

/**
 * Narrow, composition-root-scoped static holder bridging the FR-T.1
 * mid-session on-save upload trigger's {@code WorldSaveHookMixin} (which has
 * no constructor our own code ever calls) into
 * {@link WorldSaveSyncService#onWorldSaved(String, Path, String)}. Same
 * static-holder shape as {@code WorldSyncToggleHookHolder}/
 * {@code WorldHostingHookHolder}. Published once at startup by
 * {@code SteamCloudSyncClientInitializer}; read by the mixin.
 */
public final class WorldSaveHookHolder {

    private static volatile WorldSaveSyncService worldSaveSyncService;

    private WorldSaveHookHolder() {
    }

    /**
     * Publishes the enabled feature's {@link WorldSaveSyncService}. Not
     * called at all when the feature is disabled/Steam unavailable, so
     * {@link #onWorldSaved} below degrades to a no-op.
     *
     * @param service the real {@link WorldSaveSyncService}
     */
    public static void publish(WorldSaveSyncService service) {
        worldSaveSyncService = service;
    }

    /**
     * The FR-T.1 hook: called by {@code WorldSaveHookMixin} whenever the
     * integrated server finishes an on-disk save. A no-op if the feature was
     * never published (disabled/Steam unavailable).
     *
     * @param worldSlug   the world's save-folder name
     * @param worldFolder the world's on-disk save folder
     * @param displayName a player-facing name for the world
     */
    public static void onWorldSaved(String worldSlug, Path worldFolder, String displayName) {
        WorldSaveSyncService service = worldSaveSyncService;
        if (service != null) {
            service.onWorldSaved(worldSlug, worldFolder, displayName);
        }
    }
}
