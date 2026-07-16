package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.WorldSyncPreference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Load/parse/serialize for {@code config/steam-cloud-sync/world-sync-preferences.json}
 * (the per-world, local-only Group 6 sync toggle list, FR6.1):
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "worlds": [
 *     { "worldSlug": "my_world_folder", "enabled": true }
 *   ]
 * }
 * }</pre>
 *
 * <p>This file is never itself Cloud-synced (Non-goals); malformed/missing
 * content falls back to an empty list with a logged warning, never by
 * throwing.
 *
 * <p>Usage example:
 * <pre>{@code
 * WorldSyncPreferencesIO io = new WorldSyncPreferencesIO();
 * WorldSyncPreferencesIO.ParseResult result = io.load(path);
 * List<WorldSyncPreference> preferences = result.preferences();
 * }</pre>
 */
public final class WorldSyncPreferencesIO {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * @param preferences the resolved preference list; never {@code null},
     *                    empty if none
     * @param warning     a human-readable warning, or {@code null} if no
     *                    fallback occurred
     */
    public record ParseResult(List<WorldSyncPreference> preferences, String warning) {
        private static ParseResult ok(List<WorldSyncPreference> preferences) {
            return new ParseResult(preferences, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(List.of(), reason);
        }
    }

    /**
     * Loads the preference list from {@code path}. If the file does not
     * exist, an empty list is returned with no warning (a brand-new device
     * has no per-world preferences yet); the file is created lazily on first
     * write via {@link #serialize(List)}, not on load.
     *
     * @param path the preferences file's location
     * @return the resolved preference list, plus an optional warning
     */
    public ParseResult load(Path path) {
        try {
            if (Files.notExists(path)) {
                return ParseResult.ok(List.of());
            }
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return ParseResult.fallback(
                    "Failed to load " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Parses {@code content} as this file's schema. Never throws.
     *
     * @param content the raw JSON text
     * @return the resolved preference list, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null) {
            return ParseResult.fallback("world-sync-preferences content was null; using an empty list.");
        }
        try {
            CloudSyncJson.JsonValue value = CloudSyncJson.parse(content);
            if (!(value instanceof CloudSyncJson.JsonObject root)) {
                return ParseResult.fallback("world-sync-preferences root was not a JSON object; using an empty list.");
            }
            List<WorldSyncPreference> preferences = new ArrayList<>();
            for (CloudSyncJson.JsonValue element : root.getArray("worlds").elements()) {
                if (!(element instanceof CloudSyncJson.JsonObject entry)) {
                    throw new CloudSyncJson.JsonSchemaException("expected each \"worlds\" entry to be an object");
                }
                preferences.add(new WorldSyncPreference(entry.getString("worldSlug"), entry.getBoolean("enabled")));
            }
            return ParseResult.ok(List.copyOf(preferences));
        } catch (RuntimeException e) {
            return ParseResult.fallback(
                    "Malformed world-sync-preferences content (" + e.getMessage() + "); using an empty list.");
        }
    }

    /**
     * Serializes {@code preferences} to this file's JSON schema.
     *
     * @param preferences the preference list to serialize
     * @return the serialized JSON text
     */
    public String serialize(List<WorldSyncPreference> preferences) {
        CloudSyncJson.JsonArray worlds = new CloudSyncJson.JsonArray();
        for (WorldSyncPreference preference : preferences) {
            worlds.add(new CloudSyncJson.JsonObject()
                    .putString("worldSlug", preference.worldSlug())
                    .putBoolean("enabled", preference.enabled()));
        }
        CloudSyncJson.JsonObject root = new CloudSyncJson.JsonObject()
                .putNumber("schemaVersion", CURRENT_SCHEMA_VERSION)
                .put("worlds", worlds);
        return CloudSyncJson.write(root);
    }

    /**
     * Writes {@code preferences} to {@code path}, creating parent
     * directories as needed.
     *
     * @param path        the preferences file's location
     * @param preferences the preference list to persist
     * @throws IOException if the write fails
     */
    public void save(Path path, List<WorldSyncPreference> preferences) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, serialize(preferences), StandardCharsets.UTF_8);
    }
}
