package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.WorldCloudMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldCloudMetadataIOTest {

    private final WorldCloudMetadataIO io = new WorldCloudMetadataIO();

    @Test
    void roundTripsAFullyPopulatedMetadata() {
        WorldCloudMetadata metadata = new WorldCloudMetadata(
                WorldCloudMetadataIO.CURRENT_SCHEMA_VERSION, "my_world", "My World", 1_700_000_000_000L,
                "1.21.11", 42L, "Survival", "Normal", false, "ab12cd34", 1_700_000_001_000L, "aGVsbG8=");

        String serialized = io.serialize(metadata);
        WorldCloudMetadataIO.ParseResult result = io.parse(serialized);

        assertThat(result.warning()).isNull();
        assertThat(result.metadata()).isEqualTo(metadata);
    }

    @Test
    void roundTripsWithEveryNullableFieldNull() {
        WorldCloudMetadata metadata = new WorldCloudMetadata(
                WorldCloudMetadataIO.CURRENT_SCHEMA_VERSION, "my_world", "My World", 1_700_000_000_000L,
                null, null, "Survival", "Normal", true, "ab12cd34", 1_700_000_001_000L, null);

        String serialized = io.serialize(metadata);
        WorldCloudMetadataIO.ParseResult result = io.parse(serialized);

        assertThat(result.warning()).isNull();
        assertThat(result.metadata()).isNotNull();
        assertThat(result.metadata().minecraftVersion()).isNull();
        assertThat(result.metadata().seed()).isNull();
        assertThat(result.metadata().iconBase64()).isNull();
        assertThat(result.metadata()).isEqualTo(metadata);
    }

    @Test
    void blankContentIsNoMetadataYetWithNoWarning() {
        WorldCloudMetadataIO.ParseResult result = io.parse("");

        assertThat(result.metadata()).isNull();
        assertThat(result.warning()).isNull();
    }

    @Test
    void nullContentIsNoMetadataYetWithNoWarning() {
        WorldCloudMetadataIO.ParseResult result = io.parse(null);

        assertThat(result.metadata()).isNull();
        assertThat(result.warning()).isNull();
    }

    @Test
    void malformedJsonNeverThrowsAndReturnsAWarning() {
        WorldCloudMetadataIO.ParseResult result = io.parse("{ not valid json");

        assertThat(result.metadata()).isNull();
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void newerSchemaVersionParsesRecognizedFieldsWithAWarning() {
        String json = """
                {
                  "schemaVersion": 2,
                  "worldSlug": "my_world",
                  "displayName": "My World",
                  "lastPlayedMillis": 1700000000000,
                  "minecraftVersion": "1.21.11",
                  "seed": 42,
                  "gameMode": "Survival",
                  "difficulty": "Normal",
                  "hardcore": false,
                  "contentSignature": "ab12cd34",
                  "syncedAtTimestamp": 1700000001000,
                  "iconBase64": null,
                  "someFutureField": "ignored"
                }
                """;

        WorldCloudMetadataIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNotNull();
        assertThat(result.metadata()).isNotNull();
        assertThat(result.metadata().worldSlug()).isEqualTo("my_world");
        assertThat(result.metadata().seed()).isEqualTo(42L);
    }
}
