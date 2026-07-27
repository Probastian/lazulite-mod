package de.lazuli.features.tweaks.services;

import java.util.List;

/**
 * Data-driven metadata describing how a single {@code TweakState.configurables()}
 * entry should be rendered/edited on the per-tweak config screen (see
 * {@code docs/specs/tweaks-panel-config-screen.md} spec §3/§6). Lives alongside
 * {@link TweakDefinitions} rather than on the {@code TweakDefinition} interface
 * itself, keeping {@code api/tweaks} untouched (additive, feature-owned data).
 *
 * @param key         matches a {@code TweakState.configurables()} map key
 * @param label       display label for the config-screen row
 * @param kind        which generic widget renders/handles this field
 * @param enumValues  non-empty only for {@link Kind#ENUM}; empty otherwise
 * @param numericMin  used only for {@link Kind#NUMERIC}
 * @param numericMax  used only for {@link Kind#NUMERIC}
 * @param numericStep used only for {@link Kind#NUMERIC}
 */
public record ConfigFieldSpec(
        String key,
        String label,
        Kind kind,
        List<String> enumValues,
        double numericMin,
        double numericMax,
        double numericStep
) {

    public enum Kind {
        BOOLEAN,
        NUMERIC,
        ENUM,
        STRING_LIST
    }

    public static ConfigFieldSpec bool(String key, String label) {
        return new ConfigFieldSpec(key, label, Kind.BOOLEAN, List.of(), Double.NaN, Double.NaN, Double.NaN);
    }

    public static ConfigFieldSpec numeric(String key, String label, double min, double max, double step) {
        return new ConfigFieldSpec(key, label, Kind.NUMERIC, List.of(), min, max, step);
    }

    public static ConfigFieldSpec enumField(String key, String label, List<String> values) {
        return new ConfigFieldSpec(key, label, Kind.ENUM, values, Double.NaN, Double.NaN, Double.NaN);
    }

    public static ConfigFieldSpec stringList(String key, String label) {
        return new ConfigFieldSpec(key, label, Kind.STRING_LIST, List.of(), Double.NaN, Double.NaN, Double.NaN);
    }
}
