package de.lazuli.features.tweaks.config;

import de.lazuli.api.tweaks.TweakId;
import de.lazuli.api.tweaks.TweakState;
import de.lazuli.features.tweaks.services.TweakDefinitions;

import java.util.EnumMap;
import java.util.Map;

/**
 * The full persisted Tweaks state (spec Configuration): one {@link TweakState}
 * per {@link TweakId}. Mirrors {@code WardrobeConfig}'s shape (a single
 * record wrapping one map), the {@code T} type {@link TweaksConfigIO} loads/
 * saves.
 *
 * @param tweaks current state per tweak; every {@link TweakId} is always
 *               present (missing entries are backfilled with that tweak's
 *               default on load, see {@link TweaksConfigIO})
 */
public record TweaksConfig(Map<TweakId, TweakState> tweaks) {

    public TweaksConfig {
        tweaks = new EnumMap<>(tweaks);
    }

    /** All tweaks at their spec-stated default state (spec Requirements T1-T12). */
    public static final TweaksConfig DEFAULT = defaults();

    private static TweaksConfig defaults() {
        Map<TweakId, TweakState> defaults = new EnumMap<>(TweakId.class);
        for (var definition : TweakDefinitions.ALL) {
            defaults.put(definition.id(), definition.defaultState());
        }
        return new TweaksConfig(defaults);
    }

    public TweakState stateOf(TweakId id) {
        return tweaks.getOrDefault(id, TweakDefinitions.byId(id).defaultState());
    }

    public TweaksConfig withState(TweakId id, TweakState state) {
        Map<TweakId, TweakState> updated = new EnumMap<>(tweaks);
        updated.put(id, state);
        return new TweaksConfig(updated);
    }
}
