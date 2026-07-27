package de.lazuli.features.tweaks.services;

/**
 * Minecraft-agnostic hook interface for T5 Custom Crosshair, reduced v1
 * scope only (spec Requirements T5 / Non-goals). Platform implementations
 * read the remaining styling configurables (outline/gap/length/thickness/
 * center-dot/color mode/RGB) directly off {@code TweakRegistry.stateOf(CUSTOM_CROSSHAIR)}
 * at render time rather than through additional interface methods here,
 * since the HUD crosshair renderer is the sole consumer and already has
 * direct registry access platform-side.
 */
public interface CustomCrosshairHook {

    boolean isCustomCrosshairActive();
}
