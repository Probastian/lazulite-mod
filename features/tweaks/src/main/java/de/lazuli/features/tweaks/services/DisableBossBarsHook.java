package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T12 Disable Boss Bars (spec Requirements T12). */
public interface DisableBossBarsHook {

    /**
     * @param bossBarName the boss bar's display text (plain string)
     * @return {@code true} if this boss bar should be hidden from the HUD
     */
    boolean shouldHideBossBar(String bossBarName);
}
