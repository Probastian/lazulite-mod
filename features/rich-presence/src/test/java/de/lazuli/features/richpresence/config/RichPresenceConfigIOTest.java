package de.lazuli.features.richpresence.config;

import de.lazuli.features.richpresence.api.RichPresenceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RichPresenceConfigIOTest {

    private final RichPresenceConfigIO io = new RichPresenceConfigIO();

    @Test
    void parseValidEnabledTrue() {
        RichPresenceConfigIO.ParseResult result = io.parse("{\n  \"enabled\": true\n}\n");
        assertThat(result.warning()).isNull();
        assertThat(result.config().enabled()).isTrue();
    }

    @Test
    void parseValidEnabledFalse() {
        RichPresenceConfigIO.ParseResult result = io.parse("{\"enabled\": false}");
        assertThat(result.warning()).isNull();
        assertThat(result.config().enabled()).isFalse();
    }

    @Test
    void parseNullFallsBackToDefault() {
        RichPresenceConfigIO.ParseResult result = io.parse(null);
        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(RichPresenceConfig.DEFAULT);
    }

    @Test
    void parseMalformedFallsBackToDefault() {
        RichPresenceConfigIO.ParseResult result = io.parse("{ not json");
        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(RichPresenceConfig.DEFAULT);
    }

    @Test
    void parseUnknownKeyFallsBackToDefault() {
        RichPresenceConfigIO.ParseResult result = io.parse("{\"enabled\": true, \"extra\": 1}");
        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(RichPresenceConfig.DEFAULT);
    }

    @Test
    void serializeRoundTrips() {
        String serialized = io.serialize(new RichPresenceConfig(false));
        RichPresenceConfigIO.ParseResult reparsed = io.parse(serialized);
        assertThat(reparsed.warning()).isNull();
        assertThat(reparsed.config().enabled()).isFalse();
    }

    @Test
    void loadCreatesDefaultFileWhenMissing(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("rich-presence.json");
        RichPresenceConfigIO.ParseResult result = io.load(path);
        assertThat(result.warning()).isNull();
        assertThat(result.config()).isEqualTo(RichPresenceConfig.DEFAULT);
        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void loadReadsExistingFile(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("rich-presence.json");
        Files.writeString(path, "{\"enabled\": false}");
        RichPresenceConfigIO.ParseResult result = io.load(path);
        assertThat(result.warning()).isNull();
        assertThat(result.config().enabled()).isFalse();
    }
}
