package de.lazuli.api.waypoints;

/**
 * The seam a platform Version Adapter implements to tell {@code
 * features/waypoints} "which scope/dimension is currently active" (spec
 * Public API), mirroring how {@code WorldSyncToggleHook}/{@code
 * MainMenuHook} already bridge a platform-supplied fact into feature code
 * without a Minecraft import leaking into the feature layer. Backed on each
 * platform by whatever world-join/server-connect lifecycle hook {@code
 * LastPlayedPointerService}'s own equivalents already use (spec R3/R9).
 */
public interface WaypointScopeResolver {

    /**
     * @return the current scope key (spec R3): a singleplayer world's
     *         save-folder name, or a multiplayer server's {@code host:port}
     *         address -- or {@code null} if no world/server is currently
     *         joined
     */
    String currentScopeKey();

    /**
     * @return the current dimension's raw identifier string (e.g. {@code
     *         "minecraft:overworld"}), or {@code null} if no world/server is
     *         currently joined
     */
    String currentDimensionId();
}
