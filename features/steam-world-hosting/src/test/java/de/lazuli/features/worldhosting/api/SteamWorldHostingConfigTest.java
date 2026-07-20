package de.lazuli.features.worldhosting.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SteamWorldHostingConfigTest {

    @Test
    void defaultIsEnabled() {
        assertThat(SteamWorldHostingConfig.DEFAULT.enabled()).isTrue();
    }

    @Test
    void recordExposesEnabledFlag() {
        assertThat(new SteamWorldHostingConfig(false).enabled()).isFalse();
        assertThat(new SteamWorldHostingConfig(true).enabled()).isTrue();
    }
}
