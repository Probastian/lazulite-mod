package de.lazuli;

import de.lazuli.api.crossworldstats.CrossWorldStatsFacade;

/**
 * Narrow, composition-root-scoped hand-off publishing this feature's
 * {@link CrossWorldStatsFacade}, so a future consumer (a {@code features/main-menu}
 * Statistics tab amendment, out of this feature's own scope) can obtain it
 * without either module importing the other. Same publish/require shape as
 * {@code ServerJoinPresenceBridgeHandoff}. Published by
 * {@link CrossWorldStatsClientInitializer}; always a real (or {@code Noop})
 * non-null facade, so {@link #require()} never returns {@code null}.
 */
public final class CrossWorldStatsBridgeHandoff {

    private static volatile CrossWorldStatsFacade facade;

    private CrossWorldStatsBridgeHandoff() {
    }

    /** Publishes {@code facade}; called once by {@link CrossWorldStatsClientInitializer}. */
    public static void publish(CrossWorldStatsFacade facade) {
        CrossWorldStatsBridgeHandoff.facade = facade;
    }

    /** @return the published {@link CrossWorldStatsFacade}. */
    public static CrossWorldStatsFacade require() {
        CrossWorldStatsFacade published = facade;
        if (published == null) {
            throw new IllegalStateException(
                    "CrossWorldStatsBridgeHandoff.require() called before CrossWorldStatsClientInitializer "
                            + "published the facade -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
