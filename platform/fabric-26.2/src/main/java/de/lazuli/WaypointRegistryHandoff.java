package de.lazuli;

import de.lazuli.waypoints.WaypointsBundle;

/**
 * Publishes the single {@link WaypointsBundle} {@code WaypointsClientInitializer}
 * constructs, so {@code MainMenuClientInitializer} can obtain the same
 * {@code WaypointRegistry}/{@code WaypointScopeResolver} instances rather
 * than constructing competing ones -- same shape as {@link TweakRegistryHandoff}.
 *
 * <p>Correctness depends only on {@code WaypointsClientInitializer} appearing
 * before {@code MainMenuClientInitializer} in this module's {@code
 * fabric.mod.json} {@code "client"} entrypoint array.
 */
public final class WaypointRegistryHandoff {

    private static volatile WaypointsBundle instance;

    private WaypointRegistryHandoff() {
    }

    public static void publish(WaypointsBundle bundle) {
        instance = bundle;
    }

    public static WaypointsBundle require() {
        WaypointsBundle published = instance;
        if (published == null) {
            throw new IllegalStateException(
                    "WaypointRegistryHandoff.require() called before WaypointsClientInitializer published a "
                            + "WaypointsBundle -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
