package de.lazuli.api.crossworldstats;

/**
 * The sole cross-feature integration surface {@code features/cross-world-stats}
 * exposes -- a small, read-only facade following the exact
 * {@code FriendServerPresenceReader}/{@code FriendsSidebarFacade}-shaped
 * "small facade interface, published via composition-root handoff"
 * precedent every other feature-to-feature integration in this repo already
 * uses. Published by each platform's {@code CrossWorldStatsClientInitializer}
 * via {@code CrossWorldStatsBridgeHandoff}; intended for a future
 * {@code features/main-menu} Statistics tab to consume (out of this
 * feature's own scope).
 */
public interface CrossWorldStatsFacade {

    /**
     * @return the local player's current cumulative, cross-world totals for
     * the active Steam account (or the offline sentinel, FR1.2); never
     * {@code null}
     */
    CrossWorldStatsSnapshot currentTotals();

    /**
     * @return the bare save-folder names (already stripped of the
     * {@code "local:"} prefix) of every local world this feature has
     * observed for the active Steam account (or the offline sentinel);
     * never {@code null}, possibly empty (batch-3-fixes BF2)
     */
    java.util.Set<String> localWorldIdsForCurrentAccount();
}
