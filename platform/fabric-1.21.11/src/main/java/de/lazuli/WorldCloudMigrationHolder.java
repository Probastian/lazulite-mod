package de.lazuli;

import de.lazuli.features.steamcloudsync.services.WorldCloudMigrationService;

/**
 * cloud-sync-uuid-identity Risk #3: bridges {@link WorldCloudMigrationService}
 * to {@code WorldsPanel}, so a completed Phase B (physical folder rename) can
 * be detected and its row-keyed caches invalidated. Same narrow,
 * composition-root-scoped static-holder shape as every other {@code *Holder}
 * in this package -- published once by {@code SteamCloudSyncClientInitializer}
 * at startup, polled by {@code WorldsPanel}.
 */
public final class WorldCloudMigrationHolder {

    private static volatile WorldCloudMigrationService instance;

    private WorldCloudMigrationHolder() {
    }

    public static void publish(WorldCloudMigrationService service) {
        instance = service;
    }

    /** @return the published service, or {@code null} if not yet published (e.g. Steam Cloud Sync disabled). */
    public static WorldCloudMigrationService getOrNull() {
        return instance;
    }
}
