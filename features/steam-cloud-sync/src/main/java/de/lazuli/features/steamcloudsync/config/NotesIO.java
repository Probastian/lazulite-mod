package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.Note;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse/serialize for the notes/waypoints local file and Cloud file (FR5.1,
 * FR5.3), same {@code schemaVersion}-wrapped shape as
 * {@link BookmarkedServersIO}:
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "entries": [
 *     { "id": "...", "text": "Diamond vein", "context": "my_world_folder",
 *       "x": 120.0, "y": 12.0, "z": -45.0, "createdAt": 1700000000000 }
 *   ]
 * }
 * }</pre>
 * {@code context}/{@code x}/{@code y}/{@code z} may each be {@code null} for
 * a pure text reminder with no attached location (FR5.1).
 *
 * <p>Usage example:
 * <pre>{@code
 * NotesIO io = new NotesIO();
 * NotesIO.ParseResult result = io.parse(fileContent);
 * List<Note> notes = result.entries();
 * }</pre>
 */
public final class NotesIO {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * @param entries the resolved note list; never {@code null}, empty if none
     * @param warning a human-readable warning, or {@code null} if no
     *                fallback occurred
     */
    public record ParseResult(List<Note> entries, String warning) {
        private static ParseResult ok(List<Note> entries) {
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
     * @return the resolved note list, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            return ParseResult.ok(List.of());
        }
        try {
            CloudSyncJson.JsonValue value = CloudSyncJson.parse(content);
            if (!(value instanceof CloudSyncJson.JsonObject root)) {
                return ParseResult.fallback("notes root was not a JSON object; using an empty list.");
            }
            List<Note> entries = new ArrayList<>();
            for (CloudSyncJson.JsonValue element : root.getArray("entries").elements()) {
                if (!(element instanceof CloudSyncJson.JsonObject entry)) {
                    throw new CloudSyncJson.JsonSchemaException("expected each \"entries\" entry to be an object");
                }
                Double x = entry.getNumberOrNull("x");
                Double y = entry.getNumberOrNull("y");
                Double z = entry.getNumberOrNull("z");
                entries.add(new Note(
                        entry.getString("id"),
                        entry.getString("text"),
                        entry.getStringOrNull("context"),
                        x, y, z,
                        entry.getLong("createdAt")));
            }
            return ParseResult.ok(List.copyOf(entries));
        } catch (RuntimeException e) {
            return ParseResult.fallback("Malformed notes content (" + e.getMessage() + "); using an empty list.");
        }
    }

    /**
     * Serializes {@code entries} to this file's JSON schema.
     *
     * @param entries the note list to serialize
     * @return the serialized JSON text
     */
    public String serialize(List<Note> entries) {
        CloudSyncJson.JsonArray array = new CloudSyncJson.JsonArray();
        for (Note note : entries) {
            CloudSyncJson.JsonObject entry = new CloudSyncJson.JsonObject()
                    .putString("id", note.id())
                    .putString("text", note.text());
            entry.put("context", note.context() == null ? CloudSyncJson.JsonNull.INSTANCE : new CloudSyncJson.JsonString(note.context()));
            entry.put("x", note.x() == null ? CloudSyncJson.JsonNull.INSTANCE : new CloudSyncJson.JsonNumber(note.x()));
            entry.put("y", note.y() == null ? CloudSyncJson.JsonNull.INSTANCE : new CloudSyncJson.JsonNumber(note.y()));
            entry.put("z", note.z() == null ? CloudSyncJson.JsonNull.INSTANCE : new CloudSyncJson.JsonNumber(note.z()));
            entry.putNumber("createdAt", note.createdAt());
            array.add(entry);
        }
        CloudSyncJson.JsonObject root = new CloudSyncJson.JsonObject()
                .putNumber("schemaVersion", CURRENT_SCHEMA_VERSION)
                .put("entries", array);
        return CloudSyncJson.write(root);
    }
}
