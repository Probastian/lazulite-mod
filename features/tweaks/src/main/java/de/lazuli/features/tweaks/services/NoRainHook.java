package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T13 No Rain (spec Requirements T13). */
public interface NoRainHook {

    /** @return true if rain/snow precipitation rendering should currently be suppressed. */
    boolean isNoRainActive();

    /** @return true if snowy-biome precipitation should also be suppressed (T13's includeSnow). */
    boolean noRainIncludesSnow();

    /** @return true if the ambient precipitation sound loop should also be suppressed (T13's includeSound). */
    boolean noRainIncludesSound();
}
