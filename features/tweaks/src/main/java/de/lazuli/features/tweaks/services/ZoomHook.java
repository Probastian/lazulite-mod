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

    /**
     * Called from the platform's mouse-scroll mixin on every raw scroll event,
     * before vanilla's own scroll handling (hotbar slot cycling / GUI scroll)
     * runs. If Zoom is enabled, currently active, and the "scrollToAdjust"
     * configurable is on, this adjusts the configured magnification in place
     * and returns {@code true} so the caller can cancel vanilla's own
     * handling of the event; otherwise it does nothing and returns
     * {@code false} so vanilla's scroll handling proceeds unaffected.
     *
     * @param verticalAmount raw vertical scroll offset for this event (as
     *                        passed to vanilla's own scroll callback)
     * @return {@code true} if this event was consumed to adjust zoom
     */
    boolean adjustZoomByScroll(double verticalAmount);
}
