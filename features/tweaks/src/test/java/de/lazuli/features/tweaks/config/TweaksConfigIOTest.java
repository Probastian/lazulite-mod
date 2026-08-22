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
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeedRescaled")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("hideHudWhileActive")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("onHurt")).isEqualTo("NOTHING");
        assertThat(result.config().stateOf(TweakId.COMPASS).enabled()).isTrue();
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showWaypoints")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showCardinals")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showHeadingReadout")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showBorder")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).enabled()).isFalse();
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("applyToChestFamily")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("applyToShulkerBox")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("groupOrder")).isEqualTo("CREATIVE_TAB_ORDER");
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("sortWithinGroup")).isEqualTo("CREATIVE_ORDER");
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("showSearchBar")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("countTextStyle")).isEqualTo("PLAIN");
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
                    "FREECAM": { "enabled": true, "configurables": { "moveSpeed": 2.5, "sprintMultiplier": 3.0, "noclip": false, "moveSpeedRescaled": true } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.FREECAM).enabled()).isTrue();
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeed")).isEqualTo(2.5);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("sprintMultiplier")).isEqualTo(3.0);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("noclip")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeedRescaled")).isEqualTo(true);
    }

    // Addendum AD-3: moveSpeed rescale + config migration.

    @Test
    void freecamMoveSpeedNoMigrationWhenKeyAbsent() {
        String json = """
                {
                  "tweaks": {
                    "FREECAM": { "enabled": false, "configurables": {} }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeed")).isEqualTo(1.0);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeedRescaled")).isEqualTo(true);
    }

    @Test
    void freecamMoveSpeedMigratesOldScaleWhenMarkerAbsent() {
        String json = """
                {
                  "tweaks": {
                    "FREECAM": { "enabled": false, "configurables": { "moveSpeed": 10.0 } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeed")).isEqualTo(1.0);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeedRescaled")).isEqualTo(true);
    }

    @Test
    void freecamMoveSpeedNotReMigratedWhenMarkerPresent() {
        String json = """
                {
                  "tweaks": {
                    "FREECAM": { "enabled": false, "configurables": { "moveSpeed": 0.5, "moveSpeedRescaled": true } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeed")).isEqualTo(0.5);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeedRescaled")).isEqualTo(true);
    }

    @Test
    void freecamMoveSpeedMigrationIsIdempotentAcrossSerializeParseRoundTrip() {
        String json = """
                {
                  "tweaks": {
                    "FREECAM": { "enabled": false, "configurables": { "moveSpeed": 10.0 } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult firstParse = io.parse(json);
        assertThat(firstParse.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeed")).isEqualTo(1.0);

        String reserialized = io.serialize(firstParse.config());
        TweaksConfigIO.ParseResult secondParse = io.parse(reserialized);

        assertThat(secondParse.warning()).isNull();
        assertThat(secondParse.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeed")).isEqualTo(1.0);
        assertThat(secondParse.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeedRescaled")).isEqualTo(true);
    }

    @Test
    void freecamMoveSpeedClampsOutOfRangeValueRegardlessOfPath() {
        String json = """
                {
                  "tweaks": {
                    "FREECAM": { "enabled": false, "configurables": { "moveSpeed": 99.0, "moveSpeedRescaled": true } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("moveSpeed")).isEqualTo(5.0);
    }

    // Addendum 2 AD-7/AD-8: hideHudWhileActive + onHurt are purely additive
    // (no migration branch, covered by the existing default-backfill overlay).

    @Test
    void freecamHideHudAndOnHurtDefaultBackfillWhenKeysAbsent() {
        String json = """
                {
                  "tweaks": {
                    "FREECAM": { "enabled": false, "configurables": { "moveSpeed": 0.5, "moveSpeedRescaled": true } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("hideHudWhileActive")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("onHurt")).isEqualTo("NOTHING");
    }

    @Test
    void freecamHideHudAndOnHurtRoundTripUnchangedWhenPresent() {
        String json = """
                {
                  "tweaks": {
                    "FREECAM": { "enabled": true, "configurables": { "moveSpeed": 1.0, "moveSpeedRescaled": true, "hideHudWhileActive": true, "onHurt": "HURT_INDICATOR" } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("hideHudWhileActive")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.FREECAM).configurables().get("onHurt")).isEqualTo("HURT_INDICATOR");
    }

    @Test
    void compassNonDefaultConfigurablesRoundTrip() {
        String json = """
                {
                  "tweaks": {
                    "COMPASS": { "enabled": false, "configurables": { "showWaypoints": false, "showCardinals": false, "showHeadingReadout": true, "showBorder": false } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.COMPASS).enabled()).isFalse();
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showWaypoints")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showCardinals")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showHeadingReadout")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showBorder")).isEqualTo(false);
    }

    @Test
    void compassMissingFromFileBackfillsToEnabledDefault() {
        // Compass tweak (spec Configuration Migration note / plan Planning
        // Prerequisite 2): a tweaks.json written before COMPASS existed has
        // no "COMPASS" key at all -- confirms the missing-TweakId fallback
        // really does default to TweakDefinitions.COMPASS.defaultState()
        // (enabled=true), not enabled=false like every other tweak's
        // fallback would be.
        String json = """
                {
                  "tweaks": {
                    "NO_RAIN": { "enabled": true, "configurables": { "includeSnow": false, "includeSound": true } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.COMPASS).enabled()).isTrue();
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showWaypoints")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showCardinals")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showHeadingReadout")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.COMPASS).configurables().get("showBorder")).isEqualTo(true);
    }

    @Test
    void lootBinNonDefaultConfigurablesRoundTrip() {
        String json = """
                {
                  "tweaks": {
                    "LOOT_BIN": { "enabled": true, "configurables": { "applyToChestFamily": false, "applyToShulkerBox": true, "groupOrder": "ALPHABETICAL", "sortWithinGroup": "COUNT_DESC", "showSearchBar": false, "countTextStyle": "WITH_STACK_HINT" } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).enabled()).isTrue();
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("applyToChestFamily")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("applyToShulkerBox")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("groupOrder")).isEqualTo("ALPHABETICAL");
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("sortWithinGroup")).isEqualTo("COUNT_DESC");
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("showSearchBar")).isEqualTo(false);
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("countTextStyle")).isEqualTo("WITH_STACK_HINT");
    }

    @Test
    void lootBinMissingFromFileBackfillsToDisabledDefault() {
        // Unlike Compass's own documented exception (defaults enabled=true),
        // LOOT_BIN follows every other tweak's usual missing-TweakId fallback:
        // enabled=false (spec R1 -- this tweak changes vanilla interaction
        // surface materially, so it must be opt-in).
        String json = """
                {
                  "tweaks": {
                    "NO_RAIN": { "enabled": true, "configurables": { "includeSnow": false, "includeSound": true } }
                  }
                }
                """;
        TweaksConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).enabled()).isFalse();
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("applyToChestFamily")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("applyToShulkerBox")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("groupOrder")).isEqualTo("CREATIVE_TAB_ORDER");
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("sortWithinGroup")).isEqualTo("CREATIVE_ORDER");
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("showSearchBar")).isEqualTo(true);
        assertThat(result.config().stateOf(TweakId.LOOT_BIN).configurables().get("countTextStyle")).isEqualTo("PLAIN");
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
