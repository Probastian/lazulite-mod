package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.WorldSyncPreference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldSyncPreferencesIOTest {

    private final WorldSyncPreferencesIO io = new WorldSyncPreferencesIO();

    @Test
    void loadMissingFileReturnsEmptyListWithNoWarning(@TempDir Path tempDir) {
        Path path = tempDir.resolve("world-sync-preferences.json");

        WorldSyncPreferencesIO.ParseResult result = io.load(path);

        assertThat(result.preferences()).isEmpty();
        assertThat(result.warning()).isNull();
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void roundTripsMultipleEntries() throws Exception {
        List<WorldSyncPreference> preferences = List.of(
                new WorldSyncPreference("world_one", true),
                new WorldSyncPreference("world_two", false));

        String serialized = io.serialize(preferences);
        WorldSyncPreferencesIO.ParseResult reparsed = io.parse(serialized);

        assertThat(reparsed.preferences()).containsExactlyElementsOf(preferences);
        assertThat(reparsed.warning()).isNull();
    }

    @Test
    void saveThenLoadRoundTrips(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("nested").resolve("world-sync-preferences.json");
        List<WorldSyncPreference> preferences = List.of(new WorldSyncPreference("world_one", true));

        io.save(path, preferences);
        WorldSyncPreferencesIO.ParseResult result = io.load(path);

        assertThat(result.preferences()).containsExactlyElementsOf(preferences);
    }

    @Test
    void malformedContentFallsBackToEmptyList() {
        WorldSyncPreferencesIO.ParseResult result = io.parse("not json");

        assertThat(result.preferences()).isEmpty();
        assertThat(result.warning()).isNotBlank();
    }

    @Test
    void malformedEntryFallsBackToEmptyList() {
        WorldSyncPreferencesIO.ParseResult result = io.parse(
                "{\"schemaVersion\": 1, \"worlds\": [{\"worldSlug\": \"x\"}]}");

        assertThat(result.preferences()).isEmpty();
        assertThat(result.warning()).isNotBlank();
    }
}
