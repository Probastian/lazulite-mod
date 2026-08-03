package de.lazuli.services.steamworks;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class SteamAppIdResolverTest {

    @Test
    void noOverridePresentFallsBackToDefaultAppId() {
        Function<String, String> noProperties = key -> null;

        assertThat(SteamAppIdResolver.resolve(noProperties)).isEqualTo(SteamAppIdResolver.DEFAULT_APP_ID);
    }

    @Test
    void validOverridePropertyIsUsed() {
        Function<String, String> properties = Map.of(SteamAppIdResolver.SYSTEM_PROPERTY, "123456")::get;

        assertThat(SteamAppIdResolver.resolve(properties)).isEqualTo(123456L);
    }

    @Test
    void blankOverrideFallsBackToDefault() {
        Function<String, String> properties = key -> "   ";

        assertThat(SteamAppIdResolver.resolve(properties)).isEqualTo(SteamAppIdResolver.DEFAULT_APP_ID);
    }

    @Test
    void unparseableOverrideFallsBackToDefaultRatherThanThrowing() {
        Function<String, String> properties = key -> "not-a-number";

        assertThat(SteamAppIdResolver.resolve(properties)).isEqualTo(SteamAppIdResolver.DEFAULT_APP_ID);
    }

    @Test
    void nullLookupFallsBackToDefaultRatherThanThrowing() {
        assertThat(SteamAppIdResolver.resolve(null)).isEqualTo(SteamAppIdResolver.DEFAULT_APP_ID);
    }
}
