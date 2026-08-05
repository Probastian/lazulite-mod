package de.lazuli.features.steamcloudsync.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parse/serialize for the FR-T.5 per-adapter last-uploaded-state file, e.g.
 * {@code cloudsyncable-upload-state.json}: a flat map from each
 * {@code CloudSyncable.cloudSyncId()} (stable identifiers like
 * {@code "options"}/{@code "servers-dat"}/{@code "cross-world-stats"}) to the
 * local file's own last-modified time (epoch millis) at the moment this
 * device last successfully uploaded it:
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "uploads": { "options": 1700000000000, "servers-dat": 1700000005000 }
 * }
 * }</pre>
 *
 * <p>Must persist across a client restart (unlike {@code WorldSyncStatusTracker}'s
 * session-only state) -- the whole point is comparing "what did this device
 * upload last time (a prior process)" against "what's on disk right now
 * (this process)". Mirrors {@link WorldFingerprintIO}'s {@code ParseResult}
 * contract.
 *
 * <p>Usage example:
 * <pre>{@code
 * CloudSyncableUploadStateIO io = new CloudSyncableUploadStateIO();
 * CloudSyncableUploadStateIO.ParseResult result = io.parse(fileContent);
 * Map<String, Long> lastUploadedMillis = result.entries();
 * }</pre>
 */
public final class CloudSyncableUploadStateIO {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * @param entries the resolved id-to-last-uploaded-millis map; never
     *                {@code null}, empty if none
     * @param warning a human-readable warning, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(Map<String, Long> entries, String warning) {
        private static ParseResult ok(Map<String, Long> entries) {
            return new ParseResult(entries, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(Map.of(), reason);
        }
    }

    /**
     * Parses {@code content} as this file's schema. Never throws.
     *
     * @param content the raw JSON text; an empty/blank/{@code null} value is
     *                treated as "no upload state recorded yet" with no warning
     * @return the resolved map, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            return ParseResult.ok(Map.of());
        }
        try {
            CloudSyncJson.JsonValue value = CloudSyncJson.parse(content);
            if (!(value instanceof CloudSyncJson.JsonObject root)) {
                return ParseResult.fallback("cloudsyncable-upload-state root was not a JSON object; using an empty map.");
            }
            Map<String, Long> entries = new LinkedHashMap<>();
            if (root.has("uploads")) {
                CloudSyncJson.JsonObject uploads = root.getObject("uploads");
                for (String key : uploads.members().keySet()) {
                    entries.put(key, uploads.getLong(key));
                }
            }
            return ParseResult.ok(Map.copyOf(entries));
        } catch (RuntimeException e) {
            return ParseResult.fallback("Malformed cloudsyncable-upload-state content (" + e.getMessage() + "); using an empty map.");
        }
    }

    /**
     * Serializes {@code entries} to this file's JSON schema.
     *
     * @param entries the id-to-last-uploaded-millis map to serialize
     * @return the serialized JSON text
     */
    public String serialize(Map<String, Long> entries) {
        CloudSyncJson.JsonObject uploads = new CloudSyncJson.JsonObject();
        for (Map.Entry<String, Long> entry : entries.entrySet()) {
            uploads.putNumber(entry.getKey(), entry.getValue());
        }
        CloudSyncJson.JsonObject root = new CloudSyncJson.JsonObject()
                .putNumber("schemaVersion", CURRENT_SCHEMA_VERSION)
                .put("uploads", uploads);
        return CloudSyncJson.write(root);
    }
}
