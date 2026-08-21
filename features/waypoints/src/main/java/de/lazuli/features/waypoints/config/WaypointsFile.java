package de.lazuli.features.waypoints.config;

import de.lazuli.api.waypoints.Waypoint;

import java.util.List;
import java.util.Map;

/**
 * Internal persistence-shape record for one scope's waypoint file (spec
 * R6/R8): {@code schemaVersion} for future migration support, {@code
 * scopeKey} embedded for round-trip/debugging (also encoded, slugged, into
 * the file's own name), and the per-dimension waypoint lists (R4). A
 * {@link Waypoint}'s own {@code dimensionId} field is redundant with its
 * containing map key here, but kept on the record itself (per the api
 * module's own shape) since {@link de.lazuli.api.waypoints.WaypointCompassHook}
 * consumers receive flat {@code List<Waypoint>} values that need to carry
 * it directly.
 */
public record WaypointsFile(int schemaVersion, String scopeKey, Map<String, List<Waypoint>> dimensions) {
}
