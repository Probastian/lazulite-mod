package de.lazuli.features.worldhosting.config;

import de.lazuli.features.worldhosting.api.SteamWorldHostingConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SteamWorldHostingConfigIOTest {

    private final SteamWorldHostingConfigIO io = new SteamWorldHostingConfigIO();

    @Test
    void parsesEnabledTrue() {
        SteamWorldHostingConfigIO.ParseResult result = io.parse("{ \"enabled\": true }");
        assertThat(result.warning()).isNull();
        assertThat(result.config().enabled()).isTrue();
    }

    @Test
    void parsesEnabledFalse() {
        SteamWorldHostingConfigIO.ParseResult result = io.parse("{ \"enabled\": false }");
        assertThat(result.warning()).isNull();
        assertThat(result.config().enabled()).isFalse();
    }

    @Test
    void serializeParseRoundTrip() {
        for (boolean enabled : new boolean[] {true, false}) {
            SteamWorldHostingConfig config = new SteamWorldHostingConfig(enabled);
            String json = io.serialize(config);
            SteamWorldHostingConfigIO.ParseResult result = io.parse(json);
            assertThat(result.warning()).isNull();
            assertThat(result.config()).isEqualTo(config);
        }
    }

    @Test
    void malformedFallsBackToDefaultWithWarning() {
        SteamWorldHostingConfigIO.ParseResult result = io.parse("{ not valid json");
        assertThat(result.config()).isEqualTo(SteamWorldHostingConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void nullContentFallsBackToDefaultWithWarning() {
        SteamWorldHostingConfigIO.ParseResult result = io.parse(null);
        assertThat(result.config()).isEqualTo(SteamWorldHostingConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void unknownKeyFallsBackToDefault() {
        SteamWorldHostingConfigIO.ParseResult result = io.parse("{ \"enabled\": true, \"extra\": 1 }");
        assertThat(result.config()).isEqualTo(SteamWorldHostingConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void missingKeyFallsBackToDefault() {
        SteamWorldHostingConfigIO.ParseResult result = io.parse("{ }");
        assertThat(result.config()).isEqualTo(SteamWorldHostingConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }
}
