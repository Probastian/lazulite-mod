package de.lazuli.features.steamcloudsync.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Load/parse/serialize for {@code config/steam-cloud-sync/world-cloud-migration.json}
 * (the small, local-only, transient migration breadcrumb file, FR2.4 of the
 * cloud-sync-uuid-identity spec):
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "entries": [
 *     {
 *       "oldFolderName": "New World",
 *       "cloudWorldId": "6f9619ff-8b86-d011-b42d-00c04fc964ff",
 *       "cloudMigrated": true,
 *       "renamed": false
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Never itself Cloud-synced (FR2.4); malformed/missing content falls back
 * to an empty list with a logged warning, never by throwing -- mirrors
 * {@link WorldSyncPreferencesIO}'s own contract exactly.
 *
 * <p>Usage example:
 * <pre>{@code
 * WorldCloudMigrationIO io = new WorldCloudMigrationIO();
 * WorldCloudMigrationIO.ParseResult result = io.load(path);
 * List<WorldCloudMigrationIO.Entry> entries = result.entries();
 * }</pre>
 */
public final class WorldCloudMigrationIO {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * One in-progress (or completed but not yet removed) migration
     * breadcrumb entry.
     *
     * @param oldFolderName the local save folder's name before Phase B renames it
     * @param cloudWorldId  the canonical string form of the minted {@code cloudWorldId}
     * @param cloudMigrated {@code true} once Phase A (Cloud-side identity
     *                      resolution) has finished for this entry
     * @param renamed       {@code true} once Phase B (physical folder rename)
     *                      has finished for this entry
     */
    public record Entry(String oldFolderName, String cloudWorldId, boolean cloudMigrated, boolean renamed) {
    }

    /**
     * @param entries the resolved breadcrumb entry list; never {@code null},
     *                empty if none
     * @param warning a human-readable warning, or {@code null} if no fallback occurred
     */
    public record ParseResult(List<Entry> entries, String warning) {
        private static ParseResult ok(List<Entry> entries) {
            return new ParseResult(entries, null);
        }

        private static ParseResult fallback(String reason) {
            return new ParseResult(List.of(), reason);
        }
    }

    /**
     * Loads the breadcrumb list from {@code path}. If the file does not
     * exist, an empty list is returned with no warning (steady-state -- this
     * file is expected to be empty most of the time).
     *
     * @param path the breadcrumb file's location
     * @return the resolved entry list, plus an optional warning
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
     * @return the resolved entry list, plus an optional warning
     */
    public ParseResult parse(String content) {
        if (content == null) {
            return ParseResult.fallback("world-cloud-migration content was null; using an empty list.");
        }
        try {
            CloudSyncJson.JsonValue value = CloudSyncJson.parse(content);
            if (!(value instanceof CloudSyncJson.JsonObject root)) {
                return ParseResult.fallback("world-cloud-migration root was not a JSON object; using an empty list.");
            }
            List<Entry> entries = new ArrayList<>();
            for (CloudSyncJson.JsonValue element : root.getArray("entries").elements()) {
                if (!(element instanceof CloudSyncJson.JsonObject entry)) {
                    throw new CloudSyncJson.JsonSchemaException("expected each \"entries\" entry to be an object");
                }
                entries.add(new Entry(
                        entry.getString("oldFolderName"),
                        entry.getString("cloudWorldId"),
                        entry.getBoolean("cloudMigrated"),
                        entry.getBoolean("renamed")));
            }
            return ParseResult.ok(List.copyOf(entries));
        } catch (RuntimeException e) {
            return ParseResult.fallback(
                    "Malformed world-cloud-migration content (" + e.getMessage() + "); using an empty list.");
        }
    }

    /**
     * Serializes {@code entries} to this file's JSON schema.
     *
     * @param entries the breadcrumb entry list to serialize
     * @return the serialized JSON text
     */
    public String serialize(List<Entry> entries) {
        CloudSyncJson.JsonArray array = new CloudSyncJson.JsonArray();
        for (Entry entry : entries) {
            array.add(new CloudSyncJson.JsonObject()
                    .putString("oldFolderName", entry.oldFolderName())
                    .putString("cloudWorldId", entry.cloudWorldId())
                    .putBoolean("cloudMigrated", entry.cloudMigrated())
                    .putBoolean("renamed", entry.renamed()));
        }
        CloudSyncJson.JsonObject root = new CloudSyncJson.JsonObject()
                .putNumber("schemaVersion", CURRENT_SCHEMA_VERSION)
                .put("entries", array);
        return CloudSyncJson.write(root);
    }

    /**
     * Writes {@code entries} to {@code path}, creating parent directories as needed.
     *
     * @param path    the breadcrumb file's location
     * @param entries the entry list to persist
     * @throws IOException if the write fails
     */
    public void save(Path path, List<Entry> entries) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, serialize(entries), StandardCharsets.UTF_8);
    }
}
