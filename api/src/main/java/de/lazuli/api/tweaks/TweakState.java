package de.lazuli.api.tweaks;

import java.util.Map;

/**
 * The persisted/runtime unit of state for one {@link TweakId} (spec Public
 * API): whether the tweak is enabled, plus its per-tweak configurables as a
 * loosely-typed string-keyed map (mirrors {@code tweaks.json}'s own shape,
 * spec Configuration). Values are plain JSON-representable types
 * ({@code Boolean}, {@code Double}/{@code Integer}, {@code String},
 * {@code java.util.List<String>}) -- callers reading a configurable are
 * expected to know its type per that tweak's own documented schema
 * (spec Requirements T1-T12), same "no per-field static typing" tradeoff
 * {@code WardrobeConfig}'s own equip map already accepts for its value type.
 *
 * <p>Deliberately does <strong>not</strong> carry a binding field (spec
 * Public API note): hotkey bindings are real, vanilla {@code KeyBinding}
 * instances held platform-side, not part of this record or this mod's JSON.
 *
 * @param enabled        whether this tweak is currently turned on
 * @param configurables  this tweak's current configurable values, keyed by
 *                       the field name documented in spec Requirements
 */
public record TweakState(boolean enabled, Map<String, Object> configurables) {

    public TweakState {
        configurables = Map.copyOf(configurables);
    }

    public Object configurable(String key) {
        return configurables.get(key);
    }

    public TweakState withEnabled(boolean newEnabled) {
        return new TweakState(newEnabled, configurables);
    }

    public TweakState withConfigurable(String key, Object value) {
        Map<String, Object> updated = new java.util.LinkedHashMap<>(configurables);
        updated.put(key, value);
        return new TweakState(enabled, updated);
    }
}
