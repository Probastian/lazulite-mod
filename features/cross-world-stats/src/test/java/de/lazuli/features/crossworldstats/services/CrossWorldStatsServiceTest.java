package de.lazuli.features.crossworldstats.services;

import de.lazuli.api.crossworldstats.CrossWorldStatsSnapshot;
import de.lazuli.api.crossworldstats.TrackedStat;
import de.lazuli.features.crossworldstats.config.AccountStats;
import de.lazuli.features.crossworldstats.config.CrossWorldStatsConfigIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CrossWorldStatsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void tickIsRateLimitedToTheConfiguredInterval(@TempDir Path dir) {
        Path configPath = dir.resolve("cross-world-stats.json");
        CrossWorldStatsService service = new CrossWorldStatsService(
                "76561197960287930", Map.of(),
                configPath, new CrossWorldStatsConfigIO(), null, Integer.MAX_VALUE / 1000);

        service.tick("world-1", () -> Map.of(TrackedStat.DEATHS, 5L));
        service.tick("world-1", () -> Map.of(TrackedStat.DEATHS, 999L));

        // Second tick is within the (effectively infinite) interval, so it
        // must not have re-read/re-merged -- first-read baseline (0 delta)
        // is still the only merge that happened.
        assertThat(service.currentTotals().totalOf(TrackedStat.DEATHS)).isZero();
    }

    @Test
    void noWorldLoadedIsANoop() {
        Path configPath = tempDir.resolve("cross-world-stats.json");
        CrossWorldStatsService service = new CrossWorldStatsService(
                "76561197960287930", Map.of(),
                configPath, new CrossWorldStatsConfigIO(), null, 0);

        service.tick(null, () -> Map.of(TrackedStat.DEATHS, 5L));

        assertThat(service.currentTotals().totalOf(TrackedStat.DEATHS)).isZero();
    }

    @Test
    void multiAccountIsolationNoCrossAccountBleed() {
        Path configPathA = tempDir.resolve("account-a.json");
        Path configPathB = tempDir.resolve("account-b.json");
        CrossWorldStatsConfigIO io = new CrossWorldStatsConfigIO();

        CrossWorldStatsService serviceA = new CrossWorldStatsService(
                "111", Map.of(), configPathA, io, null, 0);
        CrossWorldStatsService serviceB = new CrossWorldStatsService(
                "222", Map.of(), configPathB, io, null, 0);

        serviceA.tick("world-1", () -> Map.of(TrackedStat.DEATHS, 5L));
        serviceA.tick("world-1", () -> Map.of(TrackedStat.DEATHS, 8L));

        serviceB.tick("world-1", () -> Map.of(TrackedStat.DEATHS, 100L));
        serviceB.tick("world-1", () -> Map.of(TrackedStat.DEATHS, 101L));

        assertThat(serviceA.currentTotals().totalOf(TrackedStat.DEATHS)).isEqualTo(3L);
        assertThat(serviceB.currentTotals().totalOf(TrackedStat.DEATHS)).isEqualTo(1L);
        assertThat(serviceA.currentTotals().accountKey()).isEqualTo("111");
        assertThat(serviceB.currentTotals().accountKey()).isEqualTo("222");
    }

    @Test
    void mergePersistsToDiskAndCanBeReloaded() {
        Path configPath = tempDir.resolve("cross-world-stats.json");
        CrossWorldStatsConfigIO io = new CrossWorldStatsConfigIO();
        CrossWorldStatsService service = new CrossWorldStatsService(
                "offline", Map.of(), configPath, io, null, 0);

        service.tick("world-1", () -> Map.of(TrackedStat.BLOCKS_MINED, 10L));
        service.tick("world-1", () -> Map.of(TrackedStat.BLOCKS_MINED, 40L));

        CrossWorldStatsConfigIO.ParseResult reloaded = io.load(configPath);
        assertThat(reloaded.warning()).isNull();
        AccountStats offline = reloaded.accounts().get("offline");
        assertThat(offline.totals().get(TrackedStat.BLOCKS_MINED)).isEqualTo(30L);
    }

    @Test
    void flushForcesAnImmediateMergeBypassingTheIntervalGate() {
        Path configPath = tempDir.resolve("cross-world-stats.json");
        CrossWorldStatsService service = new CrossWorldStatsService(
                "offline", Map.of(), configPath, new CrossWorldStatsConfigIO(),
                null, Integer.MAX_VALUE / 1000);

        service.tick("world-1", () -> Map.of(TrackedStat.DEATHS, 1L));
        service.flush("world-1", () -> Map.of(TrackedStat.DEATHS, 4L));

        CrossWorldStatsSnapshot snapshot = service.currentTotals();
        assertThat(snapshot.totalOf(TrackedStat.DEATHS)).isEqualTo(3L);
    }

    @Test
    void flushIsANoopWithNoWorldLoaded() {
        // BF-4-2: flush() is now the DISCONNECT-triggered call site too --
        // confirm its existing null-worldId no-op guard is unaffected by the
        // removal of the enabled-config gate.
        Path configPath = tempDir.resolve("cross-world-stats.json");
        CrossWorldStatsService service = new CrossWorldStatsService(
                "offline", Map.of(), configPath, new CrossWorldStatsConfigIO(), null, 0);

        service.flush(null, () -> Map.of(TrackedStat.DEATHS, 4L));

        assertThat(service.currentTotals().totalOf(TrackedStat.DEATHS)).isZero();
    }
}
