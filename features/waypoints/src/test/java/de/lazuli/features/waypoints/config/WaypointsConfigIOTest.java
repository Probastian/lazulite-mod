package de.lazuli.features.waypoints.config;

import de.lazuli.api.waypoints.Waypoint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WaypointsConfigIOTest {

    private final WaypointsConfigIO io = new WaypointsConfigIO();

    @Test
    void parseRoundTripsSpecIllustrativeShapeVerbatim() {
        String json = """
                {
                  "schemaVersion": 1,
                  "scopeKey": "my_world_folder",
                  "dimensions": {
                    "minecraft:overworld": [
                      {
                        "id": "6a1e2222-0000-0000-0000-000000000000",
                        "name": "Base",
                        "x": 120, "y": 68, "z": -45,
                        "color": -13312256,
                        "createdAtMillis": 1700000000000
                      }
                    ],
                    "minecraft:the_nether": []
                  }
                }
                """;

        WaypointsConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        WaypointsFile file = result.file();
        assertThat(file.schemaVersion()).isEqualTo(1);
        assertThat(file.scopeKey()).isEqualTo("my_world_folder");
        assertThat(file.dimensions().get("minecraft:the_nether")).isEmpty();
        List<Waypoint> overworld = file.dimensions().get("minecraft:overworld");
        assertThat(overworld).hasSize(1);
        Waypoint waypoint = overworld.get(0);
        assertThat(waypoint.id()).isEqualTo("6a1e2222-0000-0000-0000-000000000000");
        assertThat(waypoint.name()).isEqualTo("Base");
        assertThat(waypoint.x()).isEqualTo(120);
        assertThat(waypoint.y()).isEqualTo(68);
        assertThat(waypoint.z()).isEqualTo(-45);
        assertThat(waypoint.dimensionId()).isEqualTo("minecraft:overworld");
        assertThat(waypoint.color()).isEqualTo(-13312256);
        assertThat(waypoint.createdAtMillis()).isEqualTo(1700000000000L);

        String reserialized = io.serialize(file);
        WaypointsConfigIO.ParseResult reparsed = io.parse(reserialized);
        assertThat(reparsed.warning()).isNull();
        assertThat(reparsed.file()).isEqualTo(file);
    }

    @Test
    void serializeThenParseRoundTripsEmptyFile() {
        WaypointsFile empty = WaypointsConfigIO.empty("play.example.com:25565");
        String serialized = io.serialize(empty);

        WaypointsConfigIO.ParseResult result = io.parse(serialized);

        assertThat(result.warning()).isNull();
        assertThat(result.file().scopeKey()).isEqualTo("play.example.com:25565");
        assertThat(result.file().dimensions()).isEmpty();
    }

    @Test
    void parseFailsClosedToEmptyOnMalformedJson() {
        WaypointsConfigIO.ParseResult result = io.parse("{ not valid json");

        assertThat(result.warning()).isNotNull();
        assertThat(result.file().dimensions()).isEmpty();
    }

    @Test
    void parseFailsClosedOnMissingTopLevelObject() {
        WaypointsConfigIO.ParseResult result = io.parse("[]");

        assertThat(result.warning()).isNotNull();
        assertThat(result.file().dimensions()).isEmpty();
    }

    @Test
    void schemaVersionNewerThanCurrentIsTolerated() {
        String json = """
                {
                  "schemaVersion": 99,
                  "scopeKey": "my_world_folder",
                  "dimensions": { "minecraft:overworld": [] }
                }
                """;

        WaypointsConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNotNull();
        assertThat(result.file().schemaVersion()).isEqualTo(99);
        assertThat(result.file().dimensions()).containsKey("minecraft:overworld");
    }

    @Test
    void loadCreatesFileWithEmptyDefaultsWhenAbsent(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        Path path = tempDir.resolve("my_world_folder.json");

        WaypointsConfigIO.ParseResult result = io.load(path, "my_world_folder");

        assertThat(result.warning()).isNull();
        assertThat(Files.exists(path)).isTrue();
        assertThat(result.file().scopeKey()).isEqualTo("my_world_folder");
        assertThat(result.file().dimensions()).isEmpty();
    }

    @Test
    void loadFailsClosedOnMalformedFile(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("my_world_folder.json");
        Files.writeString(path, "not json at all");

        WaypointsConfigIO.ParseResult result = io.load(path, "my_world_folder");

        assertThat(result.warning()).isNotNull();
        assertThat(result.file().dimensions()).isEmpty();
    }

    @Test
    void loadThenReadsBackExistingContent(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        Path path = tempDir.resolve("my_world_folder.json");
        Map<String, List<Waypoint>> dimensions = Map.of("minecraft:overworld",
                List.of(new Waypoint("id-1", "Home", 0, 64, 0, "minecraft:overworld", -1, 1L)));
        io.load(path, "my_world_folder"); // first-create
        WaypointsFile toWrite = new WaypointsFile(WaypointsConfigIO.CURRENT_SCHEMA_VERSION, "my_world_folder", dimensions);
        try {
            Files.writeString(path, io.serialize(toWrite));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        WaypointsConfigIO.ParseResult result = io.load(path, "my_world_folder");

        assertThat(result.warning()).isNull();
        assertThat(result.file().dimensions().get("minecraft:overworld")).hasSize(1);
        assertThat(result.file().dimensions().get("minecraft:overworld").get(0).name()).isEqualTo("Home");
    }
}
