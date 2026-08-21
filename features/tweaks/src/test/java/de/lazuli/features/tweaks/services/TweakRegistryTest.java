package de.lazuli.features.tweaks.services;

import de.lazuli.api.tweaks.TweakId;
import de.lazuli.features.tweaks.config.TweaksConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TweakRegistryTest {

    @Test
    void setEnabledMutatesStateAndInvokesSaveCallbackOnce() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<TweaksConfig> lastSaved = new AtomicReference<>();
        TweakRegistry registry = new TweakRegistry(TweaksConfig.DEFAULT, config -> {
            saveCount.incrementAndGet();
            lastSaved.set(config);
        });

        registry.setEnabled(TweakId.ZOOM, true);

        assertThat(saveCount.get()).isEqualTo(1);
        assertThat(registry.stateOf(TweakId.ZOOM).enabled()).isTrue();
        assertThat(lastSaved.get().stateOf(TweakId.ZOOM).enabled()).isTrue();
    }

    @Test
    void setConfigurableMutatesStateAndInvokesSaveCallbackOnce() {
        AtomicInteger saveCount = new AtomicInteger();
        TweakRegistry registry = new TweakRegistry(TweaksConfig.DEFAULT, config -> saveCount.incrementAndGet());

        registry.setConfigurable(TweakId.ZOOM, "magnification", 8.0);

        assertThat(saveCount.get()).isEqualTo(1);
        assertThat(registry.stateOf(TweakId.ZOOM).configurables().get("magnification")).isEqualTo(8.0);
    }

    @Test
    void stateOfReturnsCurrentNotStaleState() {
        TweakRegistry registry = new TweakRegistry(TweaksConfig.DEFAULT, config -> { });

        assertThat(registry.stateOf(TweakId.ANTI_DROP).enabled()).isFalse();
        registry.setEnabled(TweakId.ANTI_DROP, true);
        assertThat(registry.stateOf(TweakId.ANTI_DROP).enabled()).isTrue();
    }

    @Test
    void allReturnsAllDefinitions() {
        TweakRegistry registry = new TweakRegistry(TweaksConfig.DEFAULT, config -> { });

        assertThat(registry.all()).hasSize(TweakDefinitions.ALL.size());
    }
}
