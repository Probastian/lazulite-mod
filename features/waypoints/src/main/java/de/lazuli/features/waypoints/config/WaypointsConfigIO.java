package de.lazuli.features.waypoints.config;

import de.lazuli.api.waypoints.Waypoint;
import de.lazuli.common.config.MainMenuJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled JSON reader/writer for one scope's waypoint file (spec R6-R8),
 * shaped like {@code WorldCloudMetadataIO}: load-or-create-with-empty-
 * defaults on first access, fail-closed to an empty {@link WaypointsFile}
 * with a human-readable warning on malformed content, write-through is the
 * caller's job (this class never writes except on first-create).
 *
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "scopeKey": "my_world_folder",
 *   "dimensions": {
 *     "minecraft:overworld": [
 *       {
 *         "id": "6a1e...-uuid",
 *         "name": "Base",
 *         "x": 120, "y": 68, "z": -45,
 *         "color": -13312256,
 *         "createdAtMillis": 1700000000000
 *       }
 *     ],
 *     "minecraft:the_nether": []
 *   }
 * }
 * }</pre>
 *
 * <p>A waypoint object's {@code dimensionId} (spec R1) is not repeated
 * inside each JSON object -- it is implied by the enclosing {@code
 * "dimensions"} map key, matching the spec's own illustrative R6 shape
 * verbatim.
 */
public final class WaypointsConfigIO {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public record ParseResult(WaypointsFile file, String warning) {

        private static ParseResult ok(WaypointsFile file) {
            return new ParseResult(file, null);
        }

        private static ParseResult ok(WaypointsFile file, String warning) {
            return new ParseResult(file, warning);
        }

        private static ParseResult fallback(String scopeKey, String reason) {
            return new ParseResult(empty(scopeKey), reason);
        }
    }

    /** An empty waypoint set for {@code scopeKey} (R7's "create with empty defaults" case). */
    public static WaypointsFile empty(String scopeKey) {
        return new WaypointsFile(CURRENT_SCHEMA_VERSION, scopeKey, Map.of());
    }

    /**
     * Load-or-create-with-empty-defaults on first access (R7/R9): if {@code
     * path} does not exist yet, an empty file for {@code scopeKey} is
     * written and returned; otherwise the existing content is parsed
     * (fail-closed to empty on malformed content).
     */
    public ParseResult load(Path path, String scopeKey) {
        try {
            if (Files.notExists(path)) {
                Path parent = path.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                WaypointsFile fresh = empty(scopeKey);
                Files.writeString(path, serialize(fresh), StandardCharsets.UTF_8);
                return ParseResult.ok(fresh);
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parse(content);
        } catch (IOException | RuntimeException e) {
            return ParseResult.fallback(scopeKey,
                    "Failed to load " + path + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            return ParseResult.fallback(null, "Waypoints file content was blank; using an empty set.");
        }
        try {
            MainMenuJson.JsonValue root = MainMenuJson.parse(content);
            if (!(root instanceof MainMenuJson.JsonObject rootObject)) {
                throw new MainMenuJson.JsonSchemaException("expected a JSON object at the top level");
            }
            int schemaVersion = rootObject.getInt("schemaVersion");
            String scopeKey = rootObject.getStringOrNull("scopeKey");
            MainMenuJson.JsonObject dimensionsObject = rootObject.getObject("dimensions");

            Map<String, List<Waypoint>> dimensions = new LinkedHashMap<>();
            for (Map.Entry<String, MainMenuJson.JsonValue> entry : dimensionsObject.members().entrySet()) {
                if (!(entry.getValue() instanceof MainMenuJson.JsonArray array)) {
                    throw new MainMenuJson.JsonSchemaException("expected \"" + entry.getKey() + "\" to be an array");
                }
                String dimensionId = entry.getKey();
                List<Waypoint> waypoints = new ArrayList<>();
                for (MainMenuJson.JsonValue element : array.elements()) {
                    if (!(element instanceof MainMenuJson.JsonObject waypointObject)) {
                        throw new MainMenuJson.JsonSchemaException("expected a waypoint object in \"" + dimensionId + "\"");
                    }
                    waypoints.add(new Waypoint(
                            waypointObject.getString("id"),
                            waypointObject.getString("name"),
                            waypointObject.getInt("x"),
                            waypointObject.getInt("y"),
                            waypointObject.getInt("z"),
                            dimensionId,
                            waypointObject.getInt("color"),
                            (long) waypointObject.getNumber("createdAtMillis")));
                }
                dimensions.put(dimensionId, waypoints);
            }

            WaypointsFile file = new WaypointsFile(schemaVersion, scopeKey, dimensions);
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                return ParseResult.ok(file, "waypoints schemaVersion " + schemaVersion
                        + " is newer than this build's " + CURRENT_SCHEMA_VERSION + "; recognized fields were still read.");
            }
            return ParseResult.ok(file);
        } catch (MainMenuJson.JsonParseException | MainMenuJson.JsonSchemaException e) {
            return ParseResult.fallback(null, "Malformed waypoints file (" + e.getMessage() + "); using an empty set.");
        }
    }

    public String serialize(WaypointsFile file) {
        MainMenuJson.JsonObject dimensionsObject = new MainMenuJson.JsonObject();
        for (Map.Entry<String, List<Waypoint>> entry : file.dimensions().entrySet()) {
            MainMenuJson.JsonArray array = new MainMenuJson.JsonArray();
            for (Waypoint waypoint : entry.getValue()) {
                MainMenuJson.JsonObject waypointObject = new MainMenuJson.JsonObject()
                        .putString("id", waypoint.id())
                        .putString("name", waypoint.name())
                        .putNumber("x", waypoint.x())
                        .putNumber("y", waypoint.y())
                        .putNumber("z", waypoint.z())
                        .putNumber("color", waypoint.color())
                        .putNumber("createdAtMillis", waypoint.createdAtMillis());
                array.add(waypointObject);
            }
            dimensionsObject.put(entry.getKey(), array);
        }
        MainMenuJson.JsonObject root = new MainMenuJson.JsonObject()
                .putNumber("schemaVersion", file.schemaVersion())
                .putString("scopeKey", file.scopeKey())
                .put("dimensions", dimensionsObject);
        return MainMenuJson.write(root);
    }
}
