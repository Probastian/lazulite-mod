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
        assertThat(result.config().stateOf(TweakId.NO_RAIN).enabled()).isFalse();
        assertThat(result.config().stateOf(TweakId.NO_RAIN).configurables().get("includeSnow")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.NO_RAIN).configurables().get("includeSound")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.FREECAM).enabled()).isFalse();
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeed")).isEqualTo(1.0);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("sprintMultiplier")).isEqualTo(2.0);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("noclip")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("showOwnBody")).isEqualTo(true);
    }

    @Test
    void noRainNonDefaultConfigurablesRoundTrip() {
        String json = """
                {
                  "tweaks": {
                    "NO_RAIN": { "enabled": true, "configurables": { "includeSnow": false, "includeSound": true } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.NO_RAIN).enabled()).isTrue();
        assertThat(result.config().stateOf(TweakId.NO_RAIN).configurables().get("includeSnow")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.NO_RAIN).configurables().get("includeSound")).isEqualTo(true);
    }

    @Test
    void freecamNonDefaultConfigurablesRoundTrip() {
        String json = """
                {
                  "tweaks": {
                    "FREECAM": { "enabled": true, "configurables": { "moveSpeed": 2.5, "sprintMultiplier": 3.0, "noclip": false, "showOwnBody": false } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.FREECAM).enabled()).isTrue();
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeed")).isEqualTo(2.5);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("sprintMultiplier")).isEqualTo(3.0);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("noclip")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("showOwnBody")).isEqualTo(false);
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
