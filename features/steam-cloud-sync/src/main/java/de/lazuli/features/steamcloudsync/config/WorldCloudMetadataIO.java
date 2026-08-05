package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.WorldCloudMetadata;

/**
 * Parse/serialize for the per-world Cloud metadata file
 * ({@code lazuli-world-meta-<slug>.json}), mirroring
 * {@link WorldFingerprintIO}'s {@code parse}/{@code serialize} shape, but for
 * a single {@link WorldCloudMetadata} object per file rather than a list
 * (this file is one-per-world, unlike the fingerprint file's
 * all-worlds-in-one-file shape):
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "worldSlug": "my_world_folder",
 *   "displayName": "My World",
 *   "lastPlayedMillis": 1700000000000,
 *   "minecraftVersion": "1.21.11",
 *   "seed": 42,
 *   "gameMode": "Survival",
 *   "difficulty": "Normal",
 *   "hardcore": false,
 *   "contentSignature": "ab12...",
 *   "syncedAtTimestamp": 1700000000000,
 *   "iconBase64": null
 * }
 * }</pre>
 *
 * <p>Usage example:
 * <pre>{@code
 * WorldCloudMetadataIO io = new WorldCloudMetadataIO();
 * WorldCloudMetadataIO.ParseResult result = io.parse(fileContent);
 * WorldCloudMetadata metadata = result.metadata(); // null if not yet synced
 * }</pre>
 */
public final class WorldCloudMetadataIO {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * @param metadata the resolved metadata, or {@code null} if the file was
     *                 blank/absent ("no metadata yet") or unparseable
     * @param warning  a human-readable warning, or {@code null} if no
     *                 fallback occurred
     */
    public record ParseResult(WorldCloudMetadata metadata, String warning) {
        private static ParseResult ok(WorldCloudMetadata metadata) {
            return new ParseResult(metadata, null);
        }

        private static ParseResult ok(WorldCloudMetadata metadata, String warning) {
            return new ParseResult(metadata, warning);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(null, reason);
        }
    }

    /**
     * Parses {@code content} as this file's schema. Never throws.
     *
     * @param content the raw JSON text; an empty/blank/{@code null} value is
     *                treated as "no metadata recorded yet" with no warning
     * @return the resolved metadata (or {@code null}), plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            return ParseResult.ok(null);
        }
        try {
            CloudSyncJson.JsonValue value = CloudSyncJson.parse(content);
            if (!(value instanceof CloudSyncJson.JsonObject root)) {
                return ParseResult.fallback("world-cloud-metadata root was not a JSON object; ignoring.");
            }
            int schemaVersion = root.getInt("schemaVersion");
            WorldCloudMetadata metadata = new WorldCloudMetadata(
                    schemaVersion,
                    root.getString("worldSlug"),
                    root.getString("displayName"),
                    root.getLong("lastPlayedMillis"),
                    root.getStringOrNull("minecraftVersion"),
                    longOrNull(root.getNumberOrNull("seed")),
                    root.getString("gameMode"),
                    root.getStringOrNull("difficulty"),
                    root.getBoolean("hardcore"),
                    root.getString("contentSignature"),
                    root.getLong("syncedAtTimestamp"),
                    root.getStringOrNull("iconBase64"));
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                return ParseResult.ok(metadata, "world-cloud-metadata schemaVersion " + schemaVersion
                        + " is newer than this build's " + CURRENT_SCHEMA_VERSION + "; recognized fields were still read.");
            }
            return ParseResult.ok(metadata);
        } catch (RuntimeException e) {
            return ParseResult.fallback("Malformed world-cloud-metadata content (" + e.getMessage() + "); ignoring.");
        }
    }

    /**
     * Serializes {@code metadata} to this file's JSON schema.
     *
     * @param metadata the metadata to serialize
     * @return the serialized JSON text
     */
    public String serialize(WorldCloudMetadata metadata) {
        CloudSyncJson.JsonObject root = new CloudSyncJson.JsonObject()
                .putNumber("schemaVersion", metadata.schemaVersion())
                .putString("worldSlug", metadata.worldSlug())
                .putString("displayName", metadata.displayName())
                .putNumber("lastPlayedMillis", metadata.lastPlayedMillis())
                .putString("minecraftVersion", metadata.minecraftVersion())
                .put("seed", metadata.seed() == null ? null : new CloudSyncJson.JsonNumber(metadata.seed()))
                .putString("gameMode", metadata.gameMode())
                .putString("difficulty", metadata.difficulty())
                .putBoolean("hardcore", metadata.hardcore())
                .putString("contentSignature", metadata.contentSignature())
                .putNumber("syncedAtTimestamp", metadata.syncedAtTimestamp())
                .putString("iconBase64", metadata.iconBase64());
        return CloudSyncJson.write(root);
    }

    private static Long longOrNull(Double value) {
        return value == null ? null : value.longValue();
    }
}
