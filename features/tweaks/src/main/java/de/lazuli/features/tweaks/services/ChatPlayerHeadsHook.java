package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T4 Show Player Heads in Chat (spec Requirements T4). */
public interface ChatPlayerHeadsHook {

    boolean isShowPlayerHeadsActive();

    /** @return {@code true} if the head icon should render before the player's name, {@code false} for after. */
    boolean headBeforeName();
}
