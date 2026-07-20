package de.lazuli.features.worldhosting.api;

/**
 * Immutable configuration for the Steam World Hosting feature.
 *
 * <p>Backed by a small JSON file (see
 * {@code de.lazuli.features.worldhosting.config.SteamWorldHostingConfigIO}):
 * <pre>{@code
 * {
 *   "enabled": true
 * }
 * }</pre>
 *
 * @param enabled master switch (FR0.3); even when {@code true}, has no effect
 *                unless {@code SteamAvailability.isSteamAvailable()}. When
 *                {@code false}, the world hosts normally but with no Steam
 *                tunnel (FR0.2-equivalent).
 */
public record SteamWorldHostingConfig(boolean enabled) {

    /**
     * The default configuration used when no config file exists yet, or when
     * an existing file fails to parse.
     */
    public static final SteamWorldHostingConfig DEFAULT = new SteamWorldHostingConfig(true);
}
