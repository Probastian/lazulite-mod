package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T6 Disable Texture Animations (spec Requirements T6). */
public interface DisableAnimationsHook {

    /**
     * @param animatedTextureId the animated texture/sprite's id
     * @return {@code true} if this texture's animation should keep ticking, {@code false} to freeze it on frame 0
     */
    boolean shouldAnimate(String animatedTextureId);
}
