package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.WorldFingerprint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldFingerprintIOTest {

    private final WorldFingerprintIO io = new WorldFingerprintIO();

    @Test
    void blankContentResolvesToEmptyListWithNoWarning() {
        WorldFingerprintIO.ParseResult result = io.parse(null);

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNull();
    }

    @Test
    void roundTripsEntries() {
        List<WorldFingerprint> entries = List.of(
                new WorldFingerprint("my_world_folder", "My World", "duck's PC", 1_700_000_000_000L));

        String serialized = io.serialize(entries);
        WorldFingerprintIO.ParseResult reparsed = io.parse(serialized);

        assertThat(reparsed.entries()).containsExactlyElementsOf(entries);
    }

    @Test
    void malformedContentFallsBackToEmptyList() {
        WorldFingerprintIO.ParseResult result = io.parse("not json");

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNotBlank();
    }
}
