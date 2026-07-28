package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T2 Force Brightness (spec Requirements T2). */
public interface ForceBrightnessHook {

    /** @return {@code true} if the world/block-light minimum should currently be forced up. */
    boolean isForceBrightnessActive();

    /**
     * @return the configured minimum brightness, in the same 0.0-1.0 domain as vanilla's
     * gamma/{@code LightmapRenderState.brightness} value (confirmed via decompiled game
     * code, not a 0-16 lightmap coordinate) -- but permitted to exceed vanilla's own 1.0
     * ceiling up to {@code 4.0}. Vanilla clamps its "Brightness" slider to 1.0, which the
     * shipped lightmap shader's {@code notGamma} curve only maps to its own normal
     * "Bright" look, not true fullbright. Values above 1.0 push that curve further so
     * shadowed areas actually reach full brightness on screen.
     */
    float minBrightness();
}
