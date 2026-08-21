package de.lazuli.api.waypoints;

import java.util.List;

/**
 * The seam a platform Version Adapter's HUD mixin calls every frame to
 * obtain the current dimension's render-ready waypoint list (spec Public
 * API). The platform mixin already has direct access to the player's own
 * position/yaw, so only the waypoint list itself needs to cross the
 * Feature -&gt; Platform boundary -- no round-trip of player position through
 * this feature's API.
 */
public interface WaypointCompassHook {

    /**
     * @return the current scope's current-dimension waypoint list (spec R4/
     *         R17/R18: only the current dimension, live in-memory state
     *         polled fresh every call) -- empty if no scope is loaded or the
     *         current dimension has no waypoints
     */
    List<Waypoint> waypointsForCurrentDimension();
}
