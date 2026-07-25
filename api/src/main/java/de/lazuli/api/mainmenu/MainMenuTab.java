package de.lazuli.api.mainmenu;

/**
 * The four tab-bar destinations on {@code MainMenuScreen} (spec FR2.1).
 *
 * <p>Selection state is nullable at the call site (no tab active is a
 * first-class, fully valid state, spec FR1.4/FR2.2) -- this enum itself
 * carries no "none" member, callers hold a {@code MainMenuTab} reference
 * that may simply be {@code null} (or an {@code Optional<MainMenuTab>},
 * implementation's choice).
 */
public enum MainMenuTab {
    /** Batch-2 FR-BB2.1: friends currently playing this game, live snapshot only. */
    HOME,
    WORLDS,
    SERVERS,
    STORE,
    WARDROBE,
    /** Batch-2 FR-BB3.4: read-only Steam achievement list. */
    ACHIEVEMENTS,
    /** Batch-2-fixes FR-F5.1: read-only vanilla Minecraft per-player statistics. */
    STATISTICS
}
