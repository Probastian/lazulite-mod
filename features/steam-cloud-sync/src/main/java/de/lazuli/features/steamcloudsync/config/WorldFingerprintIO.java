package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.WorldFingerprint;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse/serialize for the FR6.6 Cloud fingerprint metadata file -- the list
 * of every world this feature has ever synced to Steam Cloud from any
 * device, used both for warn-before-overwrite conflict detection (FR6.6) and
 * cloud-only-world detection (FR6.8):
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "worlds": [
 *     { "worldSlug": "my_world_folder", "displayName": "My World",
 *       "deviceLabel": "duck's PC", "syncedAtTimestamp": 1700000000000 }
 *   ]
 * }
 * }</pre>
 *
 * <p>Usage example:
 * <pre>{@code
 * WorldFingerprintIO io = new WorldFingerprintIO();
 * WorldFingerprintIO.ParseResult result = io.parse(fileContent);
 * List<WorldFingerprint> fingerprints = result.entries();
 * }</pre>
 */
public final class WorldFingerprintIO {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * @param entries the resolved fingerprint list; never {@code null},
     *                empty if none
     * @param warning a human-readable warning, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(List<WorldFingerprint> entries, String warning) {
        private static ParseResult ok(List<WorldFingerprint> entries) {
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
     *                treated as "no fingerprints recorded yet" with no warning
     * @return the resolved fingerprint list, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            return ParseResult.ok(List.of());
        }
        try {
            CloudSyncJson.JsonValue value = CloudSyncJson.parse(content);
            if (!(value instanceof CloudSyncJson.JsonObject root)) {
                return ParseResult.fallback("world-fingerprint root was not a JSON object; using an empty list.");
            }
            List<WorldFingerprint> entries = new ArrayList<>();
            for (CloudSyncJson.JsonValue element : root.getArray("worlds").elements()) {
                if (!(element instanceof CloudSyncJson.JsonObject entry)) {
                    throw new CloudSyncJson.JsonSchemaException("expected each \"worlds\" entry to be an object");
                }
                entries.add(new WorldFingerprint(
                        entry.getString("worldSlug"),
                        entry.getString("displayName"),
                        entry.getString("deviceLabel"),
                        entry.getLong("syncedAtTimestamp")));
            }
            return ParseResult.ok(List.copyOf(entries));
        } catch (RuntimeException e) {
            return ParseResult.fallback("Malformed world-fingerprint content (" + e.getMessage() + "); using an empty list.");
        }
    }

    /**
     * Serializes {@code entries} to this file's JSON schema.
     *
     * @param entries the fingerprint list to serialize
     * @return the serialized JSON text
     */
    public String serialize(List<WorldFingerprint> entries) {
        CloudSyncJson.JsonArray array = new CloudSyncJson.JsonArray();
        for (WorldFingerprint entry : entries) {
            array.add(new CloudSyncJson.JsonObject()
                    .putString("worldSlug", entry.worldSlug())
                    .putString("displayName", entry.displayName())
                    .putString("deviceLabel", entry.deviceLabel())
                    .putNumber("syncedAtTimestamp", entry.syncedAtTimestamp()));
        }
        CloudSyncJson.JsonObject root = new CloudSyncJson.JsonObject()
                .putNumber("schemaVersion", CURRENT_SCHEMA_VERSION)
                .put("worlds", array);
        return CloudSyncJson.write(root);
    }
}
