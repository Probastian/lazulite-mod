package de.lazuli.features.tweaks.services;

/**
 * Minecraft-agnostic hook interface for the Compass tweak (spec Public API),
 * mirroring {@link CustomCrosshairHook}'s exact shape/rationale: the master
 * enable/disable gets its own dedicated method, while the remaining boolean
 * sub-options ({@code showWaypoints}/{@code showCardinals}/{@code
 * showHeadingReadout}/{@code showBorder}) are read via a single generic
 * string-keyed accessor rather than one dedicated interface method each,
 * since the HUD compass bar mixin is the sole consumer and already has this
 * same generic-accessor shape available on its sibling hook.
 */
public interface CompassHook {

    boolean isCompassActive();

    Object compassConfigurable(String key);
}
