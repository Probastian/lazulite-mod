package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.WorldSyncAncestor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldSyncAncestorIOTest {

    private final WorldSyncAncestorIO io = new WorldSyncAncestorIO();

    @Test
    void blankContentResolvesToEmptyListWithNoWarning() {
        WorldSyncAncestorIO.ParseResult result = io.parse(null);

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNull();
    }

    @Test
    void roundTripsEntries() {
        List<WorldSyncAncestor> entries = List.of(
                new WorldSyncAncestor("my_world_folder", "duck's PC", 1_700_000_000_000L));

        String serialized = io.serialize(entries);
        WorldSyncAncestorIO.ParseResult reparsed = io.parse(serialized);

        assertThat(reparsed.entries()).containsExactlyElementsOf(entries);
    }

    @Test
    void malformedContentFallsBackToEmptyList() {
        WorldSyncAncestorIO.ParseResult result = io.parse("not json");

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNotBlank();
    }
}
