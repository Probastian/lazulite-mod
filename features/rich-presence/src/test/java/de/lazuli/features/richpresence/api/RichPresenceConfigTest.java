package de.lazuli.features.richpresence.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RichPresenceConfigTest {

    @Test
    void defaultIsEnabled() {
        assertThat(RichPresenceConfig.DEFAULT.enabled()).isTrue();
    }

    @Test
    void recordExposesEnabledFlag() {
        assertThat(new RichPresenceConfig(false).enabled()).isFalse();
        assertThat(new RichPresenceConfig(true).enabled()).isTrue();
    }
}
