package de.lazuli.api.crossworldstats;

/**
 * A curated, Minecraft-registry-agnostic identity for one of this feature's
 * fixed v1 tracked statistics (spec FR4.1). Consumable by
 * {@code features/main-menu}'s future Statistics tab without importing any
 * vanilla {@code Stat}/{@code StatType} class directly.
 *
 * <p>The mapping from each constant here to one or more concrete vanilla
 * {@code Stat}/{@code StatType} registry keys lives entirely in each
 * platform's own Version Adapter ({@code CrossWorldStatsMergeHook}), never in
 * this {@code api} module or in {@code features/cross-world-stats}' own
 * {@code services} package (both stay free of any Minecraft import).
 */
public enum TrackedStat {
    /** Cumulative time played, in ticks (20 ticks/second, vanilla's own unit). */
    PLAY_TIME_TICKS,
    /** Cumulative count of blocks mined, summed across every block type. */
    BLOCKS_MINED,
    /** Cumulative count of mobs killed, summed across every entity type. */
    MOB_KILLS,
    /** Cumulative count of player deaths. */
    DEATHS,
    /** Cumulative count of items crafted, summed across every item type. */
    ITEMS_CRAFTED,
    /** Cumulative distance traveled, in centimeters, summed across every vanilla movement-mode stat. */
    DISTANCE_TRAVELED_CM
}
