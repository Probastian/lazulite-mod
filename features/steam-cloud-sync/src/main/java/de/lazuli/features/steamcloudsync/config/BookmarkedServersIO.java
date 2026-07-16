package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.BookmarkedServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse/serialize for the bookmarked-servers local file and Cloud file
 * (both share the same {@code { "schemaVersion": 1, "entries": [...] } }
 * shape, FR3.1):
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "entries": [
 *     { "id": "...", "label": "My Server", "address": "play.example.com:25565", "addedAt": 1700000000000 }
 *   ]
 * }
 * }</pre>
 *
 * <p>Malformed content falls back to an empty list, never by throwing -- the
 * caller ({@code BookmarkedServersService}) is expected to log the returned
 * warning.
 *
 * <p>Usage example:
 * <pre>{@code
 * BookmarkedServersIO io = new BookmarkedServersIO();
 * BookmarkedServersIO.ParseResult result = io.parse(fileContent);
 * String reserialized = io.serialize(result.entries());
 * }</pre>
 */
public final class BookmarkedServersIO {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * @param entries the resolved bookmark list; never {@code null}, empty
     *                if none
     * @param warning a human-readable warning, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(List<BookmarkedServer> entries, String warning) {
        private static ParseResult ok(List<BookmarkedServer> entries) {
            return new ParseResult(entries, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(List.of(), reason);
        }
    }

    /**
     * Parses {@code content} as this file's schema. Never throws.
     *
     * @param content the raw JSON text; an empty/blank/{@code null} value is
     *                treated as "no file yet" and resolves to an empty list
     *                with no warning
     * @return the resolved bookmark list, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            return ParseResult.ok(List.of());
        }
        try {
            CloudSyncJson.JsonValue value = CloudSyncJson.parse(content);
            if (!(value instanceof CloudSyncJson.JsonObject root)) {
                return ParseResult.fallback("bookmarked-servers root was not a JSON object; using an empty list.");
            }
            List<BookmarkedServer> entries = new ArrayList<>();
            for (CloudSyncJson.JsonValue element : root.getArray("entries").elements()) {
                if (!(element instanceof CloudSyncJson.JsonObject entry)) {
                    throw new CloudSyncJson.JsonSchemaException("expected each \"entries\" entry to be an object");
                }
                entries.add(new BookmarkedServer(
                        entry.getString("id"),
                        entry.getString("label"),
                        entry.getString("address"),
                        entry.getLong("addedAt")));
            }
            return ParseResult.ok(List.copyOf(entries));
        } catch (RuntimeException e) {
            return ParseResult.fallback("Malformed bookmarked-servers content (" + e.getMessage() + "); using an empty list.");
        }
    }

    /**
     * Serializes {@code entries} to this file's JSON schema.
     *
     * @param entries the bookmark list to serialize
     * @return the serialized JSON text
     */
    public String serialize(List<BookmarkedServer> entries) {
        CloudSyncJson.JsonArray array = new CloudSyncJson.JsonArray();
        for (BookmarkedServer entry : entries) {
            array.add(new CloudSyncJson.JsonObject()
                    .putString("id", entry.id())
                    .putString("label", entry.label())
                    .putString("address", entry.address())
                    .putNumber("addedAt", entry.addedAt()));
        }
        CloudSyncJson.JsonObject root = new CloudSyncJson.JsonObject()
                .putNumber("schemaVersion", CURRENT_SCHEMA_VERSION)
                .put("entries", array);
        return CloudSyncJson.write(root);
    }
}
