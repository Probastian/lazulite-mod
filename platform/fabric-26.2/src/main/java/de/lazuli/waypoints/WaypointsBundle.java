package de.lazuli.waypoints;

import de.lazuli.api.waypoints.WaypointScopeResolver;
import de.lazuli.features.waypoints.services.WaypointRegistry;

/**
 * The two objects the Waypoint Manager panel UI needs, published together
 * via {@code WaypointRegistryHandoff} -- mirrors {@code TweaksBundle}'s
 * shape (registry plus whatever else that panel needs beyond it; Waypoints
 * has no keybindings per spec Non-goals, so a {@link WaypointScopeResolver}
 * takes that slot instead, giving the panel "what is the player's current
 * dimension right now" for R20's default-dimension-selector behavior and
 * R21's "Add at current position" availability check).
 */
public record WaypointsBundle(WaypointRegistry registry, WaypointScopeResolver scopeResolver) {
}
