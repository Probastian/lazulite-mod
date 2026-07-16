package de.lazuli.features.steamcloudsync.api;

/**
 * A single bookmarked multiplayer server (Group 3, FR3.1), owned entirely by
 * this feature -- never read from or written to vanilla Minecraft's own
 * {@code servers.dat}.
 *
 * <p>Usage example:
 * <pre>{@code
 * BookmarkedServer bookmark = new BookmarkedServer(
 *         UUID.randomUUID().toString(), "My Server", "play.example.com:25565",
 *         System.currentTimeMillis());
 * }</pre>
 *
 * @param id        a UUID-string uniquely identifying this bookmark
 * @param label     a human-readable label for the server
 * @param address   the server address (host:port)
 * @param addedAt   epoch-millis timestamp of when this bookmark was created
 */
public record BookmarkedServer(String id, String label, String address, long addedAt) {
}
