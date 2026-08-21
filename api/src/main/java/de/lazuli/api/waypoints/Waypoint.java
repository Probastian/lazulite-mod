package de.lazuli.api.waypoints;

/**
 * A single player-authored waypoint (spec R1): stable across rename/recolor
 * via {@code id}, block-coordinate precision (spec R1's own rationale: the
 * compass bar's bearing/distance math does not benefit from sub-block
 * precision at HUD-strip render scale), and a raw {@code dimensionId} string
 * (not an enum) so custom/modded dimensions work without a code change.
 *
 * <p>No Minecraft imports, matching {@code api/tweaks}'s existing precedent
 * for this module ({@code architecture.md}'s api-layer dependency rule).
 *
 * @param id              a stable UUID string, survives rename/recolor
 * @param name             player-facing, freeform text
 * @param x                block x coordinate
 * @param y                block y coordinate
 * @param z                block z coordinate
 * @param dimensionId      raw dimension identifier string, e.g.
 *                         {@code "minecraft:overworld"}
 * @param color            packed ARGB int (spec R5, deterministic from
 *                         {@code id})
 * @param createdAtMillis  epoch millis, for stable sort order/auditability
 */
public record Waypoint(String id, String name, int x, int y, int z, String dimensionId, int color, long createdAtMillis) {
}
