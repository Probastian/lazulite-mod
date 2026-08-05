package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.SteamCloudSyncConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SteamCloudSyncConfigIOTest {

    private final SteamCloudSyncConfigIO configIO = new SteamCloudSyncConfigIO();

    @Test
    void loadCreatesFileWithDefaultsWhenMissing(@TempDir Path tempDir) {
        Path path = tempDir.resolve("steam-cloud-sync.json");

        SteamCloudSyncConfigIO.ParseResult result = configIO.load(path);

        assertThat(Files.exists(path)).isTrue();
        assertThat(result.config()).isEqualTo(SteamCloudSyncConfig.DEFAULT);
        assertThat(result.warning()).isNull();
    }

    @Test
    void roundTripsNonDefaultValues() {
        SteamCloudSyncConfig config = new SteamCloudSyncConfig(1, true, false, true, false, true, false);

        String serialized = configIO.serialize(config);
        SteamCloudSyncConfigIO.ParseResult reparsed = configIO.parse(serialized);

        assertThat(reparsed.config()).isEqualTo(config);
        assertThat(reparsed.warning()).isNull();
    }

    /**
     * Request 1 (cloud-sync-threshold-and-full-sync-only): an old on-disk
     * config file written before {@code maxWorldArchiveSizeMb}/
     * {@code allowSelectiveFallback} were removed from the schema must still
     * parse successfully -- those now-obsolete keys are simply ignored, not
     * migrated, per the "new truth, no migration" decision.
     */
    @Test
    void parsingAnOldConfigFileWithNowObsoleteKeysStillSucceeds() {
        String legacyJson = "{"
                + "\"schemaVersion\": 1, \"enabled\": true, \"syncSettings\": true, "
                + "\"syncAccessibility\": true, \"syncBookmarkedServers\": true, "
                + "\"syncContinuePointer\": true, \"syncNotes\": true, "
                + "\"maxWorldArchiveSizeMb\": 50, \"allowSelectiveFallback\": true"
                + "}";

        SteamCloudSyncConfigIO.ParseResult result = configIO.parse(legacyJson);

        assertThat(result.warning()).isNull();
        assertThat(result.config()).isEqualTo(new SteamCloudSyncConfig(1, true, true, true, true, true, true));
    }

    @Test
    void malformedJsonFallsBackToDefaults() {
        SteamCloudSyncConfigIO.ParseResult result = configIO.parse("not json");

        assertThat(result.config()).isEqualTo(SteamCloudSyncConfig.DEFAULT);
        assertThat(result.warning()).isNotBlank();
    }

    @Test
    void missingFieldFallsBackToDefaults() {
        SteamCloudSyncConfigIO.ParseResult result = configIO.parse("{\"schemaVersion\": 1, \"enabled\": true}");

        assertThat(result.config()).isEqualTo(SteamCloudSyncConfig.DEFAULT);
        assertThat(result.warning()).isNotBlank();
    }

    @Test
    void wrongTypeFallsBackToDefaults() {
        String badJson = configIO.serialize(SteamCloudSyncConfig.DEFAULT).replace("\"enabled\": true", "\"enabled\": \"yes\"");

        SteamCloudSyncConfigIO.ParseResult result = configIO.parse(badJson);

        assertThat(result.config()).isEqualTo(SteamCloudSyncConfig.DEFAULT);
        assertThat(result.warning()).isNotBlank();
    }

    @Test
    void malformedExistingFileNeverThrows(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("steam-cloud-sync.json");
        Files.writeString(path, "{ not valid");

        SteamCloudSyncConfigIO.ParseResult result = configIO.load(path);

        assertThat(result.config()).isEqualTo(SteamCloudSyncConfig.DEFAULT);
        assertThat(result.warning()).isNotBlank();
    }
}
