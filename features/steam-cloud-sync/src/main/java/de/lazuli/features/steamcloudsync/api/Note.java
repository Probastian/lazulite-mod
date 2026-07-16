package de.lazuli.features.steamcloudsync.api;

/**
 * A single personal note/waypoint (Group 5, FR5.1), loosely tied to a
 * world/server: {@link #context()} and the coordinate fields are optional,
 * populated only if the player attached a location when creating the note.
 * This supports both pure text reminders (no context) and location-bound
 * waypoints (with context and coordinates).
 *
 * <p>Usage example:
 * <pre>{@code
 * // A pure text reminder, no location:
 * Note reminder = new Note(UUID.randomUUID().toString(), "Buy more torches",
 *         null, null, null, null, System.currentTimeMillis());
 *
 * // A location-bound waypoint:
 * Note waypoint = new Note(UUID.randomUUID().toString(), "Diamond vein",
 *         "my_world_folder", 120.0, 12.0, -45.0, System.currentTimeMillis());
 * }</pre>
 *
 * @param id        a UUID-string uniquely identifying this note
 * @param text      the note's text content
 * @param context   the world folder name or server address this note was
 *                   created in, or {@code null} if not location-bound
 * @param x         x coordinate, or {@code null} if not location-bound
 * @param y         y coordinate, or {@code null} if not location-bound
 * @param z         z coordinate, or {@code null} if not location-bound
 * @param createdAt epoch-millis timestamp of when this note was created
 */
public record Note(String id, String text, String context, Double x, Double y, Double z, long createdAt) {
}
