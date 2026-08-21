package de.lazuli.waypoints;

import de.lazuli.api.waypoints.WaypointCompassHook;

/**
 * Publishes the single {@link WaypointCompassHook} instance so the
 * compass-bar HUD mixin can reach it directly without threading it through
 * a constructor -- mirrors {@code de.lazuli.tweaks.TweakEngineHandoff}'s
 * identical mixin-facing handoff shape.
 */
public final class WaypointEngineHandoff {

    private static volatile WaypointCompassHook instance;

    private WaypointEngineHandoff() {
    }

    public static void publish(WaypointCompassHook hook) {
        instance = hook;
    }

    public static WaypointCompassHook require() {
        WaypointCompassHook published = instance;
        if (published == null) {
            throw new IllegalStateException(
                    "WaypointEngineHandoff.require() called before WaypointsClientInitializer published a WaypointCompassHook.");
        }
        return published;
    }
}
