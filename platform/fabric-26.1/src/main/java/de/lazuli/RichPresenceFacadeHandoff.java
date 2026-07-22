package de.lazuli;

import de.lazuli.api.richpresence.RichPresenceFacade;

/**
 * Narrow, composition-root-scoped hand-off publishing this module's
 * {@link RichPresenceFacade} instance (FR-RP6), so a future
 * {@code FriendsSidebarClientInitializer} amendment can consume it without
 * either feature depending on the other at compile time. Same publish/require,
 * {@code volatile}-static shape as every other hand-off in this module.
 */
public final class RichPresenceFacadeHandoff {

    private static volatile RichPresenceFacade instance;

    private RichPresenceFacadeHandoff() {
    }

    /** Publishes {@code facade}; called once by {@link RichPresenceClientInitializer}. */
    public static void publish(RichPresenceFacade facade) {
        instance = facade;
    }

    /**
     * @return the previously-published facade
     * @throws IllegalStateException if called before {@link #publish} -- check
     *                                this module's {@code fabric.mod.json}
     *                                {@code "client"} entrypoint order
     */
    public static RichPresenceFacade require() {
        RichPresenceFacade published = instance;
        if (published == null) {
            throw new IllegalStateException(
                    "RichPresenceFacadeHandoff.require() called before RichPresenceClientInitializer published a "
                            + "RichPresenceFacade -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
