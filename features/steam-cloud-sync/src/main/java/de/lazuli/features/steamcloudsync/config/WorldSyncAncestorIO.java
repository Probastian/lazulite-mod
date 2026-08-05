package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.WorldSyncAncestor;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse/serialize for the F20e local-only ancestor-tracking file
 * ({@code world-sync-ancestor-cache.json}), mirroring
 * {@link WorldFingerprintIO}'s shape:
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "worlds": [
 *     { "worldSlug": "my_world_folder", "deviceLabel": "duck's PC",
 *       "syncedAtTimestamp": 1700000000000 }
 *   ]
 * }
 * }</pre>
 *
 * <p>Unlike {@link WorldFingerprintIO}, this file is never read from or
 * written to Cloud -- it is a purely local bookkeeping file.
 *
 * <p>Usage example:
 * <pre>{@code
 * WorldSyncAncestorIO io = new WorldSyncAncestorIO();
 * WorldSyncAncestorIO.ParseResult result = io.parse(fileContent);
 * List<WorldSyncAncestor> ancestors = result.entries();
 * }</pre>
 */
public final class WorldSyncAncestorIO {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * @param entries the resolved ancestor list; never {@code null}, empty
     *                if none
     * @param warning a human-readable warning, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(List<WorldSyncAncestor> entries, String warning) {
        private static ParseResult ok(List<WorldSyncAncestor> entries) {
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
     *                treated as "no ancestors recorded yet" with no warning
     * @return the resolved ancestor list, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            return ParseResult.ok(List.of());
        }
        try {
            CloudSyncJson.JsonValue value = CloudSyncJson.parse(content);
            if (!(value instanceof CloudSyncJson.JsonObject root)) {
                return ParseResult.fallback("world-sync-ancestor root was not a JSON object; using an empty list.");
            }
            List<WorldSyncAncestor> entries = new ArrayList<>();
            for (CloudSyncJson.JsonValue element : root.getArray("worlds").elements()) {
                if (!(element instanceof CloudSyncJson.JsonObject entry)) {
                    throw new CloudSyncJson.JsonSchemaException("expected each \"worlds\" entry to be an object");
                }
                entries.add(new WorldSyncAncestor(
                        entry.getString("worldSlug"),
                        entry.getString("deviceLabel"),
                        entry.getLong("syncedAtTimestamp")));
            }
            return ParseResult.ok(List.copyOf(entries));
        } catch (RuntimeException e) {
            return ParseResult.fallback("Malformed world-sync-ancestor content (" + e.getMessage() + "); using an empty list.");
        }
    }

    /**
     * Serializes {@code entries} to this file's JSON schema.
     *
     * @param entries the ancestor list to serialize
     * @return the serialized JSON text
     */
    public String serialize(List<WorldSyncAncestor> entries) {
        CloudSyncJson.JsonArray array = new CloudSyncJson.JsonArray();
        for (WorldSyncAncestor entry : entries) {
            array.add(new CloudSyncJson.JsonObject()
                    .putString("worldSlug", entry.worldSlug())
                    .putString("deviceLabel", entry.deviceLabel())
                    .putNumber("syncedAtTimestamp", entry.syncedAtTimestamp()));
        }
        CloudSyncJson.JsonObject root = new CloudSyncJson.JsonObject()
                .putNumber("schemaVersion", CURRENT_SCHEMA_VERSION)
                .put("worlds", array);
        return CloudSyncJson.write(root);
    }
}
