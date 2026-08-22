package de.lazuli.features.tweaks.services;

import de.lazuli.api.tweaks.TweakId;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Per-{@link TweakId} ordered lists of {@link ConfigFieldSpec}, hardcoding the
 * table from {@code docs/plans/tweaks-panel-config-screen.md} §2. Row order
 * matches {@link TweakDefinitions}' own {@code map(...)} key order for that
 * tweak. Sibling lookup to {@link TweakDefinitions#byId(TweakId)} -- kept out
 * of the {@code TweakDefinition} interface to avoid touching {@code api/tweaks}.
 */
public final class ConfigSchemas {

    private ConfigSchemas() {
    }

    private static final Map<TweakId, List<ConfigFieldSpec>> ALL = new EnumMap<>(TweakId.class);

    static {
        ALL.put(TweakId.ANTI_DROP, List.of(
                ConfigFieldSpec.stringList("whitelist", "Whitelist"),
                ConfigFieldSpec.bool("shiftQForceDrop", "Shift+Q Force Drop")
        ));

        ALL.put(TweakId.FORCE_BRIGHTNESS, List.of(
                // Range intentionally exceeds vanilla's gamma-slider ceiling of 1.0.
                // Confirmed via decompiled game code (LightmapRenderStateExtractor.extract /
                // LightmapTextureManager.update on all 3 platforms) that state.brightness /
                // GameOptions.getGamma() IS already a 0.0-1.0 domain identical to the vanilla
                // "Brightness" option -- NOT a 0-16 lightmap coordinate. But that same 0.0-1.0
                // domain, read from the shipped lightmap.fsh/lightmap shader, feeds a
                // `mix(color, notGamma(color), BrightnessFactor)` curve; at BrightnessFactor
                // capped to vanilla's own max (1.0) this only reaches vanilla's normal
                // "Bright" setting, which still leaves dim/shadowed areas visibly darkened.
                // Allowing values above 1.0 pushes the mix() extrapolation harder toward the
                // notGamma-brightened color (hardware-clamped to opaque white on output),
                // which is the same "gamma > 1" trick fullbright resource packs rely on.
                ConfigFieldSpec.numeric("minBrightness", "Min Brightness", 0.0, 4.0, 0.25)
        ));

        ALL.put(TweakId.CHAT_FILTER, List.of(
                ConfigFieldSpec.bool("useBuiltInFilterList", "Use Built-In Filter List"),
                ConfigFieldSpec.stringList("customTerms", "Custom Terms")
        ));

        ALL.put(TweakId.CHAT_PLAYER_HEADS, List.of(
                ConfigFieldSpec.enumField("position", "Position", List.of("BEFORE", "AFTER"))
        ));

        ALL.put(TweakId.CUSTOM_CROSSHAIR, List.of(
                ConfigFieldSpec.bool("outline", "Outline"),
                ConfigFieldSpec.numeric("gap", "Gap", 0.0, 20.0, 0.5),
                ConfigFieldSpec.numeric("length", "Length", 1.0, 30.0, 0.5),
                ConfigFieldSpec.numeric("thickness", "Thickness", 0.5, 10.0, 0.5),
                ConfigFieldSpec.bool("centerDot", "Center Dot"),
                // No confirmed call site reads colorMode in any platform's
                // TweakHooksImpl/HUD crosshair renderer (plan §1/§7 risk #4);
                // shipping only the default value as the sole known enum
                // option -- widget still renders/cycles safely (no-op).
                ConfigFieldSpec.enumField("colorMode", "Color Mode", List.of("VANILLA")),
                ConfigFieldSpec.numeric("colorR", "Color R", 0.0, 255.0, 5.0),
                ConfigFieldSpec.numeric("colorG", "Color G", 0.0, 255.0, 5.0),
                ConfigFieldSpec.numeric("colorB", "Color B", 0.0, 255.0, 5.0)
        ));

        ALL.put(TweakId.DISABLE_ANIMATIONS, List.of(
                ConfigFieldSpec.enumField("mode", "Mode", List.of("ALL", "WHITELIST", "BLACKLIST")),
                ConfigFieldSpec.stringList("list", "List")
        ));

        ALL.put(TweakId.DISABLE_PARTICLES, List.of(
                ConfigFieldSpec.enumField("mode", "Mode", List.of("ALL", "WHITELIST", "BLACKLIST")),
                ConfigFieldSpec.stringList("list", "List")
        ));

        ALL.put(TweakId.HIDE_PLAYER_NAMES, List.of(
                ConfigFieldSpec.enumField("mode", "Mode", List.of("GLOBAL", "RANGE_INCLUSIVE", "RANGE_EXCLUSIVE")),
                ConfigFieldSpec.numeric("range", "Range", 0.0, 64.0, 1.0)
        ));

        ALL.put(TweakId.CLEAR_WATER, List.of(
                ConfigFieldSpec.numeric("opacity", "Opacity", 0.0, 1.0, 0.05)
        ));

        ALL.put(TweakId.DISABLE_COSMETICS, List.of(
                ConfigFieldSpec.bool("HEAD", "Head"),
                ConfigFieldSpec.bool("TORSO", "Torso"),
                ConfigFieldSpec.bool("LEGS", "Legs"),
                ConfigFieldSpec.bool("FEET", "Feet")
        ));

        ALL.put(TweakId.ZOOM, List.of(
                ConfigFieldSpec.bool("holdToZoom", "Hold To Zoom"),
                ConfigFieldSpec.bool("transition", "Transition"),
                ConfigFieldSpec.numeric("transitionDurationMs", "Transition Duration (ms)", 0.0, 1000.0, 50.0),
                ConfigFieldSpec.numeric("magnification", "Magnification", 1.5, 10.0, 0.5),
                ConfigFieldSpec.bool("scrollToAdjust", "Scroll To Adjust")
        ));

        ALL.put(TweakId.DISABLE_BOSS_BARS, List.of(
                ConfigFieldSpec.enumField("mode", "Mode", List.of("ALL", "WHITELIST", "BLACKLIST")),
                ConfigFieldSpec.stringList("list", "List")
        ));

        ALL.put(TweakId.NO_RAIN, List.of(
                ConfigFieldSpec.bool("includeSnow", "Include Snow"),
                ConfigFieldSpec.bool("includeSound", "Include Sound")
        ));

        ALL.put(TweakId.FREECAM, List.of(
                ConfigFieldSpec.numeric("moveSpeed", "Move Speed", 0.25, 5.0, 0.25),
                ConfigFieldSpec.numeric("sprintMultiplier", "Sprint Multiplier", 1.0, 5.0, 0.5),
                ConfigFieldSpec.bool("noclip", "Noclip")
                // no moveSpeedRescaled row (internal-only marker, no ConfigFieldSpec entry)
        ));

        ALL.put(TweakId.COMPASS, List.of(
                ConfigFieldSpec.bool("showWaypoints", "Show Waypoints"),
                ConfigFieldSpec.bool("showCardinals", "Show Cardinal Letters"),
                ConfigFieldSpec.bool("showHeadingReadout", "Show Heading Readout"),
                ConfigFieldSpec.bool("showBorder", "Show Border")
        ));
    }

    public static List<ConfigFieldSpec> fieldsFor(TweakId id) {
        List<ConfigFieldSpec> fields = ALL.get(id);
        if (fields == null) {
            throw new IllegalStateException("No ConfigFieldSpec list registered for " + id);
        }
        return fields;
    }
}
