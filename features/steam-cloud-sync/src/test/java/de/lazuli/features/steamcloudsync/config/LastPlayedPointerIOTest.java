package de.lazuli.features.steamcloudsync.config;

import de.lazuli.features.steamcloudsync.api.LastPlayedPointer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LastPlayedPointerIOTest {

    private final LastPlayedPointerIO io = new LastPlayedPointerIO();

    @Test
    void blankContentResolvesToEmptyOptionalWithNoWarning() {
        LastPlayedPointerIO.ParseResult result = io.parse(null);

        assertThat(result.pointer()).isEmpty();
        assertThat(result.warning()).isNull();
    }

    @Test
    void roundTripsWorldPointer() {
        LastPlayedPointer pointer = new LastPlayedPointer(LastPlayedPointer.Type.WORLD, "My World", "my_world_folder", 1_700_000_000_000L);

        String serialized = io.serialize(pointer);
        LastPlayedPointerIO.ParseResult reparsed = io.parse(serialized);

        assertThat(reparsed.pointer()).contains(pointer);
    }

    @Test
    void roundTripsServerPointer() {
        LastPlayedPointer pointer = new LastPlayedPointer(LastPlayedPointer.Type.SERVER, "My Server", "play.example.com:25565", 1_700_000_000_000L);

        String serialized = io.serialize(pointer);
        LastPlayedPointerIO.ParseResult reparsed = io.parse(serialized);

        assertThat(reparsed.pointer()).contains(pointer);
    }

    @Test
    void invalidTypeFallsBackToEmptyOptionalWithWarning() {
        LastPlayedPointerIO.ParseResult result = io.parse(
                "{\"type\": \"NOT_A_TYPE\", \"name\": \"n\", \"identifier\": \"i\", \"timestamp\": 1}");

        assertThat(result.pointer()).isEmpty();
        assertThat(result.warning()).isNotBlank();
    }

    @Test
    void malformedContentFallsBackToEmptyOptional() {
        LastPlayedPointerIO.ParseResult result = io.parse("not json");

        assertThat(result.pointer()).isEmpty();
        assertThat(result.warning()).isNotBlank();
    }
}
