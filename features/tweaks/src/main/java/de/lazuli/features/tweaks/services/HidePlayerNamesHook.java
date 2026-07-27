package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T8 Hide Player Names (spec Requirements T8). */
public interface HidePlayerNamesHook {

    /**
     * @param distanceToPlayer distance in blocks from the local player to the target player whose name tag is about to render
     * @return {@code true} if that name tag should be hidden
     */
    boolean shouldHideName(double distanceToPlayer);
}
