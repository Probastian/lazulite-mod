package de.lazuli.features.steamcloudsync.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceLabelResolverTest {

    @Test
    void bothPresentJoinsWithAt() {
        assertThat(DeviceLabelResolver.resolve("duck", "ducks-pc")).isEqualTo("duck@ducks-pc");
    }

    @Test
    void onlyUserNamePresent() {
        assertThat(DeviceLabelResolver.resolve("duck", null)).isEqualTo("duck");
        assertThat(DeviceLabelResolver.resolve("duck", "  ")).isEqualTo("duck");
    }

    @Test
    void onlyHostNamePresent() {
        assertThat(DeviceLabelResolver.resolve(null, "ducks-pc")).isEqualTo("ducks-pc");
        assertThat(DeviceLabelResolver.resolve("  ", "ducks-pc")).isEqualTo("ducks-pc");
    }

    @Test
    void neitherPresentFallsBackToUnknownDevice() {
        assertThat(DeviceLabelResolver.resolve(null, null)).isEqualTo(DeviceLabelResolver.UNKNOWN_DEVICE_LABEL);
        assertThat(DeviceLabelResolver.resolve("", "")).isEqualTo(DeviceLabelResolver.UNKNOWN_DEVICE_LABEL);
    }
}
