package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T14 Freecam (spec Requirements T14). */
public interface FreecamHook {

    /** @return true if Freecam is currently enabled and toggled on. */
    boolean isFreecamActive();

    /** @return the configured move-speed multiplier (T14's moveSpeed). */
    float freecamMoveSpeed();

    /** @return the configured sprint speed multiplier (T14's sprintMultiplier). */
    float freecamSprintMultiplier();

    /** @return true if the camera's own flight should ignore block collision (T14's noclip). Entity collision is never applied to the camera regardless of this value. */
    boolean freecamNoclip();
}
