package de.lazuli.features.serverjoinpresence.config;

import de.lazuli.features.serverjoinpresence.api.ServerJoinPresenceConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerJoinPresenceConfigIOTest {

    private final ServerJoinPresenceConfigIO io = new ServerJoinPresenceConfigIO();

    @Test
    void parsesEnabledTrue() {
        ServerJoinPresenceConfigIO.ParseResult result = io.parse("{ \"enabled\": true }");
        assertThat(result.warning()).isNull();
        assertThat(result.config().enabled()).isTrue();
    }

    @Test
    void parsesEnabledFalse() {
        ServerJoinPresenceConfigIO.ParseResult result = io.parse("{ \"enabled\": false }");
        assertThat(result.warning()).isNull();
        assertThat(result.config().enabled()).isFalse();
    }

    @Test
    void serializeParseRoundTrip() {
        for (boolean enabled : new boolean[] {true, false}) {
            ServerJoinPresenceConfig config = new ServerJoinPresenceConfig(enabled);
            String json = io.serialize(config);
            ServerJoinPresenceConfigIO.ParseResult result = io.parse(json);
            assertThat(result.warning()).isNull();
            assertThat(result.config()).isEqualTo(config);
        }
    }

    @Test
    void malformedFallsBackToDefaultWithWarning() {
        ServerJoinPresenceConfigIO.ParseResult result = io.parse("{ not valid json");
        assertThat(result.config()).isEqualTo(ServerJoinPresenceConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void nullContentFallsBackToDefaultWithWarning() {
        ServerJoinPresenceConfigIO.ParseResult result = io.parse(null);
        assertThat(result.config()).isEqualTo(ServerJoinPresenceConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void unknownKeyFallsBackToDefault() {
        ServerJoinPresenceConfigIO.ParseResult result = io.parse("{ \"enabled\": true, \"extra\": 1 }");
        assertThat(result.config()).isEqualTo(ServerJoinPresenceConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void missingKeyFallsBackToDefault() {
        ServerJoinPresenceConfigIO.ParseResult result = io.parse("{ }");
        assertThat(result.config()).isEqualTo(ServerJoinPresenceConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }
}
