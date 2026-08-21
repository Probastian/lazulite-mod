package de.lazuli.features.tweaks.services;

import de.lazuli.api.tweaks.TweakDefinition;
import de.lazuli.api.tweaks.TweakId;
import de.lazuli.api.tweaks.TweakState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 12 static {@link TweakDefinition} instances (spec F1, one per
 * {@link TweakId}), with translation keys and default states per spec
 * Requirements T1-T12.
 */
public final class TweakDefinitions {

    private TweakDefinitions() {
    }

    private static TweakDefinition of(TweakId id, String description, Map<String, Object> defaultConfigurables, boolean hasSecondary) {
        TweakState defaultState = new TweakState(false, defaultConfigurables);
        String translationKey = "tweak.lazuli." + id.name().toLowerCase(java.util.Locale.ROOT);
        return new TweakDefinition() {
            @Override
            public TweakId id() {
                return id;
            }

            @Override
            public String translationKey() {
                return translationKey;
            }

            @Override
            public TweakState defaultState() {
                return defaultState;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public boolean hasSecondaryKeyBinding() {
                return hasSecondary;
            }
        };
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static final TweakDefinition ANTI_DROP = of(TweakId.ANTI_DROP,
            "Prevents accidentally dropping items; Shift+Q force-drops and a whitelist/hotkey control what's protected.",
            map("whitelist", List.of(), "shiftQForceDrop", true), true);

    public static final TweakDefinition FORCE_BRIGHTNESS = of(TweakId.FORCE_BRIGHTNESS,
            "Forces a minimum screen brightness so dark areas never go fully black.",
            map("minBrightness", 4.0), false);

    public static final TweakDefinition CHAT_FILTER = of(TweakId.CHAT_FILTER,
            "Hides chat messages containing filtered or custom blacklisted terms.",
            map("useBuiltInFilterList", true, "customTerms", List.of()), false);

    public static final TweakDefinition CHAT_PLAYER_HEADS = of(TweakId.CHAT_PLAYER_HEADS,
            "Shows the sender's player head next to their chat messages.",
            map("position", "BEFORE"), false);

    public static final TweakDefinition CUSTOM_CROSSHAIR = of(TweakId.CUSTOM_CROSSHAIR,
            "Replaces the vanilla crosshair with a customizable outline, gap, length, thickness and color.",
            map("outline", true, "gap", 2.0, "length", 6.0, "thickness", 1.0,
                    "centerDot", false, "colorMode", "VANILLA", "colorR", 255.0, "colorG", 255.0, "colorB", 255.0),
            false);

    public static final TweakDefinition DISABLE_ANIMATIONS = of(TweakId.DISABLE_ANIMATIONS,
            "Disables animated block/item textures, either entirely or for a whitelist/blacklist.",
            map("mode", "ALL", "list", List.of()), false);

    public static final TweakDefinition DISABLE_PARTICLES = of(TweakId.DISABLE_PARTICLES,
            "Disables particle effects, either entirely or for a whitelist/blacklist.",
            map("mode", "ALL", "list", List.of()), false);

    public static final TweakDefinition HIDE_PLAYER_NAMES = of(TweakId.HIDE_PLAYER_NAMES,
            "Hides other players' nametags globally or within/beyond a distance range.",
            map("mode", "GLOBAL", "range", 16.0), false);

    public static final TweakDefinition CLEAR_WATER = of(TweakId.CLEAR_WATER,
            "Reduces the underwater overlay's opacity for clearer visibility.",
            map("opacity", 0.0), false);

    public static final TweakDefinition DISABLE_COSMETICS = of(TweakId.DISABLE_COSMETICS,
            "Hides other players' worn armor per-slot (head, torso, legs, feet).",
            map("HEAD", false, "TORSO", false, "LEGS", false, "FEET", false), false);

    public static final TweakDefinition ZOOM = of(TweakId.ZOOM,
            "Adds a hold-or-toggle zoom with adjustable magnification and transition speed.",
            map("holdToZoom", true, "transition", true, "transitionDurationMs", 150.0,
                    "magnification", 4.0, "scrollToAdjust", true),
            false);

    public static final TweakDefinition DISABLE_BOSS_BARS = of(TweakId.DISABLE_BOSS_BARS,
            "Hides boss bars, either entirely or for a whitelist/blacklist.",
            map("mode", "ALL", "list", List.of()), false);

    public static final TweakDefinition NO_RAIN = of(TweakId.NO_RAIN,
            "Suppresses rain/snow precipitation rendering and its ambient sound, leaving lightning untouched.",
            map("includeSnow", true, "includeSound", true), false);

    public static final TweakDefinition FREECAM = of(TweakId.FREECAM,
            "Toggles a detached, free-flying spectator-style camera with configurable speed and block noclip.",
            map("moveSpeed", 1.0, "sprintMultiplier", 2.0, "noclip", true, "showOwnBody", true), false);

    public static final List<TweakDefinition> ALL = List.of(
            ANTI_DROP, FORCE_BRIGHTNESS, CHAT_FILTER, CHAT_PLAYER_HEADS, CUSTOM_CROSSHAIR,
            DISABLE_ANIMATIONS, DISABLE_PARTICLES, HIDE_PLAYER_NAMES, CLEAR_WATER,
            DISABLE_COSMETICS, ZOOM, DISABLE_BOSS_BARS, NO_RAIN, FREECAM);

    public static TweakDefinition byId(TweakId id) {
        for (TweakDefinition def : ALL) {
            if (def.id() == id) {
                return def;
            }
        }
        throw new IllegalStateException("No TweakDefinition registered for " + id);
    }
}
