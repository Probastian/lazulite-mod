package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T9 Clear Water (spec Requirements T9). */
public interface ClearWaterHook {

    /** @return the underwater overlay opacity multiplier to apply, 0.0 (fully clear) - 1.0 (vanilla default), or 1.0 if disabled. */
    float underwaterOverlayOpacityMultiplier();
}
