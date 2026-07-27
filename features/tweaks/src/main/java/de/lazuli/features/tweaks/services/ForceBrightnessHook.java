package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T2 Force Brightness (spec Requirements T2). */
public interface ForceBrightnessHook {

    /** @return {@code true} if the world/block-light minimum should currently be forced up. */
    boolean isForceBrightnessActive();

    /** @return the configured minimum brightness, normalized 0.0 (no boost) - 1.0 (full fullbright). */
    float minBrightness();
}
