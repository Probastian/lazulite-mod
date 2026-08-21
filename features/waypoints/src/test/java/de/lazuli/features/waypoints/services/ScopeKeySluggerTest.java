package de.lazuli.features.waypoints.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeKeySluggerTest {

    @Test
    void alreadySafeStringPassesThroughUnchanged() {
        assertThat(ScopeKeySlugger.slug("my_world_folder")).isEqualTo("my_world_folder");
    }

    @Test
    void serverAddressColonIsReplaced() {
        assertThat(ScopeKeySlugger.slug("play.example.com:25565")).isEqualTo("play_example_com_25565");
    }

    @Test
    void upperCaseIsFolded() {
        assertThat(ScopeKeySlugger.slug("MyWorld")).isEqualTo("myworld");
    }

    @Test
    void emptyInputDoesNotThrow() {
        assertThat(ScopeKeySlugger.slug("")).isNotBlank();
    }

    @Test
    void nullInputDoesNotThrow() {
        assertThat(ScopeKeySlugger.slug(null)).isNotBlank();
    }
}
