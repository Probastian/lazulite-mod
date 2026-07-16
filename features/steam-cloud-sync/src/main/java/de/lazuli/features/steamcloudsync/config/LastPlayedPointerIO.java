package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.LastPlayedPointer;

import java.util.Optional;

/**
 * Parse/serialize for the "continue where you left off" pointer local file
 * and Cloud file (FR4.1), a single record rather than a list:
 * <pre>{@code
 * {
 *   "type": "WORLD",
 *   "name": "My World",
 *   "identifier": "my_world_folder",
 *   "timestamp": 1700000000000
 * }
 * }</pre>
 *
 * <p>Usage example:
 * <pre>{@code
 * LastPlayedPointerIO io = new LastPlayedPointerIO();
 * LastPlayedPointerIO.ParseResult result = io.parse(fileContent);
 * Optional<LastPlayedPointer> pointer = result.pointer();
 * }</pre>
 */
public final class LastPlayedPointerIO {

    /**
     * @param pointer the resolved pointer, or empty if none has ever been
     *                recorded (or if parsing failed)
     * @param warning a human-readable warning, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(Optional<LastPlayedPointer> pointer, String warning) {
        private static ParseResult ok(LastPlayedPointer pointer) {
            return new ParseResult(Optional.of(pointer), null);
        }

        private static ParseResult empty() {
            return new ParseResult(Optional.empty(), null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(Optional.empty(), reason);
        }
    }

    /**
     * Parses {@code content} as this file's schema. Never throws.
     *
     * @param content the raw JSON text; an empty/blank/{@code null} value is
     *                treated as "no pointer recorded yet" with no warning
     * @return the resolved pointer (possibly empty), plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            return ParseResult.empty();
        }
        try {
            CloudSyncJson.JsonValue value = CloudSyncJson.parse(content);
            if (!(value instanceof CloudSyncJson.JsonObject root)) {
                return ParseResult.fallback("continue-pointer root was not a JSON object; ignoring.");
            }
            String typeText = root.getString("type");
            LastPlayedPointer.Type type;
            try {
                type = LastPlayedPointer.Type.valueOf(typeText);
            } catch (IllegalArgumentException e) {
                return ParseResult.fallback("continue-pointer \"type\" was not WORLD/SERVER (\"" + typeText + "\"); ignoring.");
            }
            LastPlayedPointer pointer = new LastPlayedPointer(
                    type,
                    root.getString("name"),
                    root.getString("identifier"),
                    root.getLong("timestamp"));
            return ParseResult.ok(pointer);
        } catch (RuntimeException e) {
            return ParseResult.fallback("Malformed continue-pointer content (" + e.getMessage() + "); ignoring.");
        }
    }

    /**
     * Serializes {@code pointer} to this file's JSON schema.
     *
     * @param pointer the pointer to serialize
     * @return the serialized JSON text
     */
    public String serialize(LastPlayedPointer pointer) {
        CloudSyncJson.JsonObject root = new CloudSyncJson.JsonObject()
                .putString("type", pointer.type().name())
                .putString("name", pointer.name())
                .putString("identifier", pointer.identifier())
                .putNumber("timestamp", pointer.timestamp());
        return CloudSyncJson.write(root);
    }
}
