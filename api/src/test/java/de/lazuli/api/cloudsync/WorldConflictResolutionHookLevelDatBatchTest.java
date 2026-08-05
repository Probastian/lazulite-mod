package de.lazuli.api.cloudsync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cloud-world-entry-parity Requirement 3b: {@link WorldConflictResolutionHook.LevelDatBatch}'s
 * additive {@code lastPlayedMillis}/{@code gameMode}/{@code hardcore} fields
 * -- {@code unreadable()} still returns all-sentinel values including the 3
 * new fields, and the 9-arg constructor compiles and round-trips.
 */
class WorldConflictResolutionHookLevelDatBatchTest {

    @Test
    void unreadableReturnsAllSentinelValuesIncludingTheThreeNewFields() {
        WorldConflictResolutionHook.LevelDatBatch batch = WorldConflictResolutionHook.LevelDatBatch.unreadable();

        assertThat(batch.seed()).isNull();
        assertThat(batch.difficulty()).isNull();
        assertThat(batch.cheatsEnabled()).isNull();
        assertThat(batch.dayCount()).isEqualTo(-1L);
        assertThat(batch.minecraftVersion()).isNull();
        assertThat(batch.readable()).isFalse();
        assertThat(batch.lastPlayedMillis()).isEqualTo(-1L);
        assertThat(batch.gameMode()).isNull();
        assertThat(batch.hardcore()).isFalse();
    }

    @Test
    void nineArgConstructorRoundTripsRealValues() {
        WorldConflictResolutionHook.LevelDatBatch batch = new WorldConflictResolutionHook.LevelDatBatch(
                42L, "Hard", true, 12L, "1.21.11", true, 1_700_000_000_000L, "Creative", true);

        assertThat(batch.seed()).isEqualTo(42L);
        assertThat(batch.difficulty()).isEqualTo("Hard");
        assertThat(batch.cheatsEnabled()).isTrue();
        assertThat(batch.dayCount()).isEqualTo(12L);
        assertThat(batch.minecraftVersion()).isEqualTo("1.21.11");
        assertThat(batch.readable()).isTrue();
        assertThat(batch.lastPlayedMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(batch.gameMode()).isEqualTo("Creative");
        assertThat(batch.hardcore()).isTrue();
    }
}
