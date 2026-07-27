package de.lazuli.features.tweaks.services;

import de.lazuli.api.tweaks.TweakDefinition;
import de.lazuli.api.tweaks.TweakId;
import de.lazuli.api.tweaks.TweakState;
import de.lazuli.features.tweaks.config.TweaksConfig;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runtime registry of every {@link TweakDefinition} plus its current
 * {@link TweakState} (spec Public API). I/O-agnostic: every mutation
 * triggers a caller-supplied save callback (constructor-injected
 * {@code Consumer<TweaksConfig>}) rather than reaching for a config path
 * itself -- actual file write happens in the platform composition root
 * ({@code TweaksClientInitializer}), matching every other {@code features/*}
 * config precedent (spec F5/F6, Events section).
 *
 * <p>Deliberately does not expose {@code keyBindingOf(TweakId): KeyBinding}
 * (spec Public API) -- {@code KeyBinding} instances are Minecraft types this
 * plain-Java module cannot depend on; the platform-side keybinding holder
 * (one per platform module, e.g. {@code TweaksKeyBindings}) is the
 * equivalent lookup surface consumed by each platform's Tweaks tab UI. See
 * {@code TweakDefinition}'s Javadoc for the full explanation of this
 * deviation.
 */
public final class TweakRegistry {

    private final Map<TweakId, TweakState> state;
    private final Consumer<TweaksConfig> onChanged;
    private final Consumer<String> toggleLogger;

    public TweakRegistry(TweaksConfig initial, Consumer<TweaksConfig> onChanged) {
        this(initial, onChanged, message -> { });
    }

    /**
     * @param toggleLogger invoked with a single line each time {@link #setEnabled}
     *                      flips a tweak on/off (constructor-injected the same way
     *                      as {@code RichPresencePublisher}'s {@code changeLogger},
     *                      since this plain-Java module has no logging framework of
     *                      its own -- the platform composition root wires this to
     *                      {@code LazuliMod.LOGGER::info}).
     */
    public TweakRegistry(TweaksConfig initial, Consumer<TweaksConfig> onChanged, Consumer<String> toggleLogger) {
        this.state = new EnumMap<>(TweakId.class);
        for (TweakDefinition definition : TweakDefinitions.ALL) {
            this.state.put(definition.id(), initial.stateOf(definition.id()));
        }
        this.onChanged = onChanged;
        this.toggleLogger = toggleLogger;
    }

    public List<TweakDefinition> all() {
        return TweakDefinitions.ALL;
    }

    public TweakState stateOf(TweakId id) {
        return state.get(id);
    }

    public synchronized void setEnabled(TweakId id, boolean enabled) {
        state.put(id, state.get(id).withEnabled(enabled));
        toggleLogger.accept("Tweak " + id + " toggled " + (enabled ? "on" : "off"));
        notifyChanged();
    }

    public synchronized void setConfigurable(TweakId id, String key, Object value) {
        state.put(id, state.get(id).withConfigurable(key, value));
        notifyChanged();
    }

    private void notifyChanged() {
        onChanged.accept(new TweaksConfig(new EnumMap<>(state)));
    }
}
