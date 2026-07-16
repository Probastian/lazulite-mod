package de.lazuli.features.steamcloudsync.api;

/**
 * A single "continue where you left off" record (Group 4, FR4.1) -- metadata
 * only, never the world/server's actual save data itself.
 *
 * <p>Usage example:
 * <pre>{@code
 * LastPlayedPointer pointer = new LastPlayedPointer(
 *         LastPlayedPointer.Type.WORLD, "My World", "my_world_folder",
 *         System.currentTimeMillis());
 * }</pre>
 *
 * @param type       whether this points at a singleplayer world or a
 *                   multiplayer server
 * @param name       a human-readable name (the world's display name, or the
 *                   server's configured name)
 * @param identifier the world's save-folder name, or the server's address
 * @param timestamp  epoch-millis timestamp of when this pointer was last
 *                   updated
 */
public record LastPlayedPointer(Type type, String name, String identifier, long timestamp) {

    /**
     * Which kind of destination a {@link LastPlayedPointer} refers to.
     */
    public enum Type {
        WORLD,
        SERVER
    }
}
