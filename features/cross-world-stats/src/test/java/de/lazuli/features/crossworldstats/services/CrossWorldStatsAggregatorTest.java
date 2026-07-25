package de.lazuli.features.crossworldstats.services;

import de.lazuli.api.crossworldstats.TrackedStat;
import de.lazuli.features.crossworldstats.config.AccountStats;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CrossWorldStatsAggregatorTest {

    private final CrossWorldStatsAggregator aggregator = new CrossWorldStatsAggregator();

    @Test
    void firstReadBaselineYieldsZeroDeltaAndRecordsBaseline() {
        // FR3.3: a (world, stat) pair never seen before must not lump-sum
        // pre-existing progress into the total on first observation.
        AccountStats existing = AccountStats.EMPTY;

        AccountStats updated = aggregator.merge(existing, "world-1", Map.of(TrackedStat.BLOCKS_MINED, 1200L));

        assertThat(updated.totals().getOrDefault(TrackedStat.BLOCKS_MINED, 0L)).isZero();
        assertThat(updated.worldBaselines().get("world-1").get(TrackedStat.BLOCKS_MINED)).isEqualTo(1200L);
    }

    @Test
    void subsequentReadAddsOnlyTheNewlyEarnedDelta() {
        AccountStats existing = new AccountStats(
                Map.of(TrackedStat.BLOCKS_MINED, 0L),
                Map.of("world-1", Map.of(TrackedStat.BLOCKS_MINED, 1200L)));

        AccountStats updated = aggregator.merge(existing, "world-1", Map.of(TrackedStat.BLOCKS_MINED, 1500L));

        assertThat(updated.totals().get(TrackedStat.BLOCKS_MINED)).isEqualTo(300L);
        assertThat(updated.worldBaselines().get("world-1").get(TrackedStat.BLOCKS_MINED)).isEqualTo(1500L);
    }

    @Test
    void repeatedReadOfUnchangedValueProducesZeroDelta() {
        AccountStats existing = new AccountStats(
                Map.of(TrackedStat.DEATHS, 50L),
                Map.of("world-1", Map.of(TrackedStat.DEATHS, 5L)));

        AccountStats updated = aggregator.merge(existing, "world-1", Map.of(TrackedStat.DEATHS, 5L));

        assertThat(updated.totals().get(TrackedStat.DEATHS)).isEqualTo(50L);
        assertThat(updated.worldBaselines().get("world-1").get(TrackedStat.DEATHS)).isEqualTo(5L);
    }

    @Test
    void negativeDeltaClampsToZeroAndRebaselinesDownward() {
        // FR2.4: a restored older backup can make a world's stat value go
        // down -- must never subtract, and must re-baseline to the lower
        // value so future increases are recognized correctly.
        AccountStats existing = new AccountStats(
                Map.of(TrackedStat.MOB_KILLS, 100L),
                Map.of("world-1", Map.of(TrackedStat.MOB_KILLS, 80L)));

        AccountStats updated = aggregator.merge(existing, "world-1", Map.of(TrackedStat.MOB_KILLS, 20L));

        assertThat(updated.totals().get(TrackedStat.MOB_KILLS)).isEqualTo(100L);
        assertThat(updated.worldBaselines().get("world-1").get(TrackedStat.MOB_KILLS)).isEqualTo(20L);

        // A later increase from the new, lower baseline is recognized correctly.
        AccountStats afterIncrease = aggregator.merge(updated, "world-1", Map.of(TrackedStat.MOB_KILLS, 25L));
        assertThat(afterIncrease.totals().get(TrackedStat.MOB_KILLS)).isEqualTo(105L);
    }

    @Test
    void multipleWorldsAccumulateIndependentlyIntoTheSameTotal() {
        AccountStats existing = AccountStats.EMPTY;

        AccountStats afterWorld1First = aggregator.merge(existing, "world-1", Map.of(TrackedStat.DEATHS, 3L));
        AccountStats afterWorld2First = aggregator.merge(afterWorld1First, "world-2", Map.of(TrackedStat.DEATHS, 1L));

        // Both first reads baseline to 0 delta.
        assertThat(afterWorld2First.totals().getOrDefault(TrackedStat.DEATHS, 0L)).isZero();

        AccountStats afterWorld1Second = aggregator.merge(afterWorld2First, "world-1", Map.of(TrackedStat.DEATHS, 5L));
        AccountStats afterWorld2Second = aggregator.merge(afterWorld1Second, "world-2", Map.of(TrackedStat.DEATHS, 4L));

        assertThat(afterWorld2Second.totals().get(TrackedStat.DEATHS)).isEqualTo(2L + 3L);
    }

    @Test
    void unrelatedStatsAndWorldsAreUntouched() {
        AccountStats existing = new AccountStats(
                Map.of(TrackedStat.DEATHS, 10L, TrackedStat.MOB_KILLS, 20L),
                Map.of("world-1", Map.of(TrackedStat.DEATHS, 1L, TrackedStat.MOB_KILLS, 2L)));

        AccountStats updated = aggregator.merge(existing, "world-1", Map.of(TrackedStat.DEATHS, 2L));

        assertThat(updated.totals().get(TrackedStat.DEATHS)).isEqualTo(11L);
        assertThat(updated.totals().get(TrackedStat.MOB_KILLS)).isEqualTo(20L);
        assertThat(updated.worldBaselines().get("world-1").get(TrackedStat.MOB_KILLS)).isEqualTo(2L);
    }
}
