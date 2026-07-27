package de.lazuli.features.tweaks.config;

import de.lazuli.api.tweaks.TweakId;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TweaksConfigIOTest {

    private final TweaksConfigIO io = new TweaksConfigIO();

    @Test
    void parseRoundTripsSerializedDefault() {
        String serialized = io.serialize(TweaksConfig.DEFAULT);
        TweaksConfigIO.ParseResult result = io.parse(serialized);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.ZOOM).configurables().get("magnification")).isEqualTo(4.0);
        assertThat(result.config().stateOf(TweakId.ANTI_DROP).enabled()).isFalse();
    }

    @Test
    void parseFailsClosedToDefaultsOnMalformedJson() {
        TweaksConfigIO.ParseResult result = io.parse("{ not valid json");

        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(TweaksConfig.DEFAULT);
    }

    @Test
    void parseFailsClosedOnMissingTopLevelObject() {
        TweaksConfigIO.ParseResult result = io.parse("[]");

        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(TweaksConfig.DEFAULT);
    }

    @Test
    void unknownTweakIdInFileIsIgnoredNotFatal() {
        String json = """
                {
                  "tweaks": {
                    "SOME_FUTURE_TWEAK": { "enabled": true, "configurables": {} },
                    "ZOOM": { "enabled": true, "configurables": { "magnification": 8, "holdToZoom": false, "transition": true, "transitionDurationMs": 150, "scrollToAdjust": true } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.ZOOM).enabled()).isTrue();
        assertThat(result.config().stateOf(TweakId.ZOOM).configurables().get("magnification")).isEqualTo(8.0);
    }

    @Test
    void loadCreatesFileWithDefaultsWhenAbsent(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("tweaks.json");

        TweaksConfigIO.ParseResult result = io.load(path);

        assertThat(result.warning()).isNull();
        assertThat(Files.exists(path)).isTrue();
        assertThat(result.config()).isEqualTo(TweaksConfig.DEFAULT);
    }

    @Test
    void loadFailsClosedOnMalformedFile(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("tweaks.json");
        Files.writeString(path, "not json at all");

        TweaksConfigIO.ParseResult result = io.load(path);

        assertThat(result.warning()).isNotNull();
        assertThat(result.config()).isEqualTo(TweaksConfig.DEFAULT);
    }
}
