package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.BookmarkedServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookmarkedServersIOTest {

    private final BookmarkedServersIO io = new BookmarkedServersIO();

    @Test
    void blankContentResolvesToEmptyListWithNoWarning() {
        BookmarkedServersIO.ParseResult result = io.parse(null);
        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNull();

        result = io.parse("   ");
        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNull();
    }

    @Test
    void roundTripsEntries() {
        List<BookmarkedServer> entries = List.of(
                new BookmarkedServer("id-1", "My Server", "play.example.com:25565", 1_700_000_000_000L));

        String serialized = io.serialize(entries);
        BookmarkedServersIO.ParseResult reparsed = io.parse(serialized);

        assertThat(reparsed.entries()).containsExactlyElementsOf(entries);
        assertThat(reparsed.warning()).isNull();
    }

    @Test
    void malformedContentFallsBackToEmptyList() {
        BookmarkedServersIO.ParseResult result = io.parse("not json");

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNotBlank();
    }

    @Test
    void missingFieldOnEntryFallsBackToEmptyList() {
        BookmarkedServersIO.ParseResult result = io.parse(
                "{\"schemaVersion\": 1, \"entries\": [{\"id\": \"x\", \"label\": \"l\"}]}");

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNotBlank();
    }
}
