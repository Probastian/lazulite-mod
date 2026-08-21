/**
 * Placeholder package: this feature needs no cross-module event bus (spec
 * Events section) -- {@link de.lazuli.features.waypoints.services.WaypointRegistry}'s
 * mutation methods write-through immediately, and consumers poll current
 * in-memory state directly each frame/render call instead.
 */
package de.lazuli.features.waypoints.events;
