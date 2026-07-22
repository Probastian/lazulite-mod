package de.lazuli.features.mainmenu.config;

import de.lazuli.api.mainmenu.WardrobeSlot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WardrobeConfigIOTest {

    private final WardrobeConfigIO io = new WardrobeConfigIO();

    @Test
    void parsesAMixOfEquippedAndUnequippedSlots() {
        WardrobeConfigIO.ParseResult result = io.parse("""
                {
                  "equipped": {
                    "HEAD": null,
                    "TORSO": "moss-cloak",
                    "LEGS": null,
                    "FEET": null
                  }
                }
                """);
        assertThat(result.warning()).isNull();
        assertThat(result.config().equipped()).containsExactly(Map.entry(WardrobeSlot.TORSO, "moss-cloak"));
    }

    @Test
    void serializeParseRoundTrip() {
        WardrobeConfig config = new WardrobeConfig(Map.of(WardrobeSlot.HEAD, "wanderer-hood", WardrobeSlot.FEET, "sturdy-boots"));
        String json = io.serialize(config);
        WardrobeConfigIO.ParseResult result = io.parse(json);
        assertThat(result.warning()).isNull();
        assertThat(result.config()).isEqualTo(config);
    }

    @Test
    void malformedFallsBackToDefaultWithWarning() {
        WardrobeConfigIO.ParseResult result = io.parse("{ not valid json");
        assertThat(result.config()).isEqualTo(WardrobeConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void nullContentFallsBackToDefaultWithWarning() {
        WardrobeConfigIO.ParseResult result = io.parse(null);
        assertThat(result.config()).isEqualTo(WardrobeConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void unknownSlotNameFallsBackToDefaultWithWarning() {
        WardrobeConfigIO.ParseResult result = io.parse("""
                {
                  "equipped": {
                    "HAT": "wanderer-hood"
                  }
                }
                """);
        assertThat(result.config()).isEqualTo(WardrobeConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void missingFileCreatesDefault(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("main-menu-wardrobe.json");
        assertThat(Files.exists(path)).isFalse();

        WardrobeConfigIO.ParseResult result = io.load(path);

        assertThat(result.warning()).isNull();
        assertThat(result.config()).isEqualTo(WardrobeConfig.DEFAULT);
        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void malformedFileFallsBackToDefaultWithWarning(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("main-menu-wardrobe.json");
        Files.writeString(path, "{ not valid json");

        WardrobeConfigIO.ParseResult result = io.load(path);

        assertThat(result.config()).isEqualTo(WardrobeConfig.DEFAULT);
        assertThat(result.warning()).isNotNull();
    }
}
