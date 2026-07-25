package de.lazuli.features.crossworldstats.config;

import de.lazuli.api.crossworldstats.TrackedStat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CrossWorldStatsConfigIOTest {

    private final CrossWorldStatsConfigIO io = new CrossWorldStatsConfigIO();

    @Test
    void parsesEmptyAccountsObject() {
        CrossWorldStatsConfigIO.ParseResult result = io.parse("{ \"accounts\": {} }");
        assertThat(result.warning()).isNull();
        assertThat(result.accounts()).isEmpty();
    }

    @Test
    void missingAccountsKeyDefaultsToEmptyMap() {
        CrossWorldStatsConfigIO.ParseResult result = io.parse("{}");
        assertThat(result.warning()).isNull();
        assertThat(result.accounts()).isEmpty();
    }

    @Test
    void staleLegacyEnabledKeyIsSilentlyIgnoredNotTreatedAsMalformed() {
        // BF-4-2: a pre-batch-4-fixes on-disk file may still carry a stray
        // "enabled" key -- must parse cleanly, not error/warn.
        CrossWorldStatsConfigIO.ParseResult result = io.parse("{ \"enabled\": false, \"accounts\": {} }");
        assertThat(result.warning()).isNull();
        assertThat(result.accounts()).isEmpty();
    }

    @Test
    void parsesMultiAccountTotalsAndBaselines() {
        String json = """
                {
                  "accounts": {
                    "76561197960287930": {
                      "totals": { "BLOCKS_MINED": 48213, "DEATHS": 14 },
                      "worldBaselines": {
                        "world-1": { "BLOCKS_MINED": 1200, "DEATHS": 1 }
                      }
                    },
                    "offline": {
                      "totals": { "DEATHS": 3 },
                      "worldBaselines": {}
                    }
                  }
                }
                """;

        CrossWorldStatsConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.accounts()).containsOnlyKeys("76561197960287930", "offline");

        AccountStats steamAccount = result.accounts().get("76561197960287930");
        assertThat(steamAccount.totals().get(TrackedStat.BLOCKS_MINED)).isEqualTo(48213L);
        assertThat(steamAccount.totals().get(TrackedStat.DEATHS)).isEqualTo(14L);
        assertThat(steamAccount.worldBaselines().get("world-1").get(TrackedStat.BLOCKS_MINED)).isEqualTo(1200L);

        AccountStats offlineAccount = result.accounts().get("offline");
        assertThat(offlineAccount.totals().get(TrackedStat.DEATHS)).isEqualTo(3L);
    }

    @Test
    void unknownTrackedStatKeyIsDroppedNotTreatedAsMalformed() {
        String json = """
                {
                  "accounts": {
                    "offline": { "totals": { "SOME_RETIRED_STAT": 5, "DEATHS": 2 }, "worldBaselines": {} }
                  }
                }
                """;

        CrossWorldStatsConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.accounts().get("offline").totals()).containsOnly(Map.entry(TrackedStat.DEATHS, 2L));
    }

    @Test
    void serializeParseRoundTrip() {
        Map<String, AccountStats> accounts = Map.of(
                "76561197960287930", new AccountStats(
                        Map.of(TrackedStat.BLOCKS_MINED, 48213L),
                        Map.of("world-1", Map.of(TrackedStat.BLOCKS_MINED, 1200L))),
                "offline", AccountStats.EMPTY);

        String json = io.serialize(accounts);
        CrossWorldStatsConfigIO.ParseResult result = io.parse(json);

        assertThat(result.warning()).isNull();
        assertThat(result.accounts()).isEqualTo(accounts);
        // BF-4-2: the persisted schema no longer contains an "enabled" key.
        assertThat(json).doesNotContain("\"enabled\"");
    }

    @Test
    void malformedJsonFallsBackToEmptyDefaultsWithWarning() {
        CrossWorldStatsConfigIO.ParseResult result = io.parse("{ not valid json");
        assertThat(result.accounts()).isEmpty();
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void nullContentFallsBackToDefaultsWithWarning() {
        CrossWorldStatsConfigIO.ParseResult result = io.parse(null);
        assertThat(result.accounts()).isEmpty();
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void wrongTypedAccountsValueIsMalformed() {
        CrossWorldStatsConfigIO.ParseResult result = io.parse("{ \"accounts\": \"not-an-object\" }");
        assertThat(result.warning()).isNotNull();
        assertThat(result.accounts()).isEmpty();
    }
}
