package de.lazuli.features.steamcloudsync.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldCloudMigrationIOTest {

    private final WorldCloudMigrationIO io = new WorldCloudMigrationIO();

    @Test
    void loadOfMissingFileReturnsEmptyListWithNoWarning() {
        WorldCloudMigrationIO.ParseResult result = io.load(Path.of("does-not-exist.json"));

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNull();
    }

    @Test
    void saveThenLoadRoundTrips(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("world-cloud-migration.json");
        List<WorldCloudMigrationIO.Entry> entries = List.of(
                new WorldCloudMigrationIO.Entry("New World", "6f9619ff-8b86-d011-b42d-00c04fc964ff", true, false));

        io.save(path, entries);
        WorldCloudMigrationIO.ParseResult result = io.load(path);

        assertThat(result.warning()).isNull();
        assertThat(result.entries()).containsExactly(entries.get(0));
    }

    @Test
    void malformedJsonFallsBackToEmptyListWithWarning() {
        WorldCloudMigrationIO.ParseResult result = io.parse("{ not valid json");

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void nonObjectRootFallsBackToEmptyListWithWarning() {
        WorldCloudMigrationIO.ParseResult result = io.parse("[]");

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void nullContentFallsBackToEmptyListWithWarning() {
        WorldCloudMigrationIO.ParseResult result = io.parse(null);

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void saveCreatesParentDirectories(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("nested").resolve("world-cloud-migration.json");

        io.save(path, List.of());

        assertThat(Files.isRegularFile(path)).isTrue();
    }

    @Test
    void serializedContentIsReadableAsUtf8Json(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("world-cloud-migration.json");
        io.save(path, List.of(new WorldCloudMigrationIO.Entry("Old Folder", "11111111-1111-1111-1111-111111111111", false, false)));

        String content = Files.readString(path, StandardCharsets.UTF_8);

        assertThat(content).contains("\"oldFolderName\": \"Old Folder\"");
        assertThat(content).contains("\"cloudMigrated\": false");
        assertThat(content).contains("\"renamed\": false");
    }
}
