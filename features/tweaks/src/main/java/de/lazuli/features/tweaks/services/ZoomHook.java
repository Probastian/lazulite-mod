package de.lazuli.features.tweaks.services;

/**
 * Minecraft-agnostic hook interface for T11 Zoom (spec Requirements T11).
 * Per-frame FOV adjustment; hold-vs-toggle and hotkey polling itself
 * ({@code KeyBinding.isDown()}/{@code wasPressed()}) happen platform-side
 * (Architecture) -- this hook only asks "is zoom currently active" (already
 * resolved by the platform's own hold/toggle state machine) and computes the
 * resulting FOV.
 */
public interface ZoomHook {

    /** @return {@code true} if zoom is currently active (held or toggled on). */
    boolean isZoomActive();

    /**
     * @param baseFov the vanilla FOV value for this frame, before zoom is applied
     * @return the FOV to actually use this frame (magnified if {@link #isZoomActive()}, with optional smooth transition applied platform-side)
     */
    float applyFov(float baseFov);
}
