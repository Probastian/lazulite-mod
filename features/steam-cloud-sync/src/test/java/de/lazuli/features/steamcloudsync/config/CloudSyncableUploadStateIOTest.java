package de.lazuli.features.steamcloudsync.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CloudSyncableUploadStateIOTest {

    private final CloudSyncableUploadStateIO io = new CloudSyncableUploadStateIO();

    @Test
    void emptyOrBlankContentParsesToAnEmptyMapWithNoWarning() {
        CloudSyncableUploadStateIO.ParseResult result = io.parse("");
        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNull();

        CloudSyncableUploadStateIO.ParseResult blank = io.parse("   ");
        assertThat(blank.entries()).isEmpty();
        assertThat(blank.warning()).isNull();

        CloudSyncableUploadStateIO.ParseResult nullContent = io.parse(null);
        assertThat(nullContent.entries()).isEmpty();
        assertThat(nullContent.warning()).isNull();
    }

    @Test
    void roundTripsSerializeAndParse() {
        Map<String, Long> entries = Map.of("options", 1_000L, "servers-dat", 2_000L, "cross-world-stats", 3_000L);

        String serialized = io.serialize(entries);
        CloudSyncableUploadStateIO.ParseResult result = io.parse(serialized);

        assertThat(result.warning()).isNull();
        assertThat(result.entries()).isEqualTo(entries);
    }

    @Test
    void malformedJsonReturnsAWarningAndAnEmptyMap() {
        CloudSyncableUploadStateIO.ParseResult result = io.parse("{ not valid json");

        assertThat(result.warning()).isNotNull();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void nonObjectRootReturnsAWarningAndAnEmptyMap() {
        CloudSyncableUploadStateIO.ParseResult result = io.parse("[1, 2, 3]");

        assertThat(result.warning()).isNotNull();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void missingUploadsFieldParsesToAnEmptyMap() {
        CloudSyncableUploadStateIO.ParseResult result = io.parse("{ \"schemaVersion\": 1 }");

        assertThat(result.warning()).isNull();
        assertThat(result.entries()).isEmpty();
    }
}
