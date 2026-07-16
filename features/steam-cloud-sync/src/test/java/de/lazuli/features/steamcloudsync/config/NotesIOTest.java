package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.Note;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotesIOTest {

    private final NotesIO io = new NotesIO();

    @Test
    void blankContentResolvesToEmptyListWithNoWarning() {
        NotesIO.ParseResult result = io.parse(null);
        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNull();
    }

    @Test
    void roundTripsPureTextReminderWithNullLocation() {
        Note note = new Note("id-1", "Buy more torches", null, null, null, null, 1_700_000_000_000L);

        String serialized = io.serialize(List.of(note));
        NotesIO.ParseResult reparsed = io.parse(serialized);

        assertThat(reparsed.entries()).containsExactly(note);
    }

    @Test
    void roundTripsLocationBoundWaypoint() {
        Note note = new Note("id-2", "Diamond vein", "my_world_folder", 120.0, 12.0, -45.0, 1_700_000_000_000L);

        String serialized = io.serialize(List.of(note));
        NotesIO.ParseResult reparsed = io.parse(serialized);

        assertThat(reparsed.entries()).containsExactly(note);
    }

    @Test
    void malformedContentFallsBackToEmptyList() {
        NotesIO.ParseResult result = io.parse("not json");

        assertThat(result.entries()).isEmpty();
        assertThat(result.warning()).isNotBlank();
    }
}
