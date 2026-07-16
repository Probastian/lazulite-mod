package de.lazuli.api.cloudsync;

/**
 * Stable, Minecraft-free abstraction over a single local world's per-world
 * Steam Cloud sync preference (FR6.1 of {@code steam-cloud-sync}'s
 * specification), consumed by a platform Version Adapter (the sync-toggle
 * icon widget injected into each <em>local</em> world's row on the vanilla
 * Singleplayer world-select screen) and implemented by
 * {@code features/steam-cloud-sync}'s own {@code WorldSyncPreferenceService}.
 *
 * <p>Usage example (from a platform Version Adapter holding a
 * constructor-injected {@code WorldSyncToggleHook}):
 * <pre>{@code
 * WorldSyncToggleHook hook = ...; // supplied by the platform composition root
 * boolean syncing = hook.isSyncEnabled(worldFolderName);
 * icon.setState(syncing ? SYNC_ENABLED_TEXTURE : SYNC_DISABLED_TEXTURE);
 * icon.onClick(() -> hook.toggleSync(worldFolderName));
 * }</pre>
 */
public interface WorldSyncToggleHook {

    /**
     * @param worldSlug the world's on-disk save-folder name
     * @return {@code true} if this world is currently opted in to Cloud
     *         world-save sync; defaults to {@code false} for a world never
     *         seen before (every world defaults to sync-disabled, per FR6.1)
     */
    boolean isSyncEnabled(String worldSlug);

    /**
     * Flips this world's sync preference: enabled becomes disabled, and vice
     * versa (a never-before-seen world is treated as currently disabled, so
     * toggling it enables it).
     *
     * @param worldSlug the world's on-disk save-folder name
     */
    void toggleSync(String worldSlug);
}
