package de.lazuli.features.richpresence.api;

/**
 * Immutable configuration for the Rich Presence Publishing feature.
 *
 * <p>Backed by a small JSON file (see
 * {@code de.lazuli.features.richpresence.config.RichPresenceConfigIO}):
 * <pre>{@code
 * {
 *   "enabled": true
 * }
 * }</pre>
 *
 * <p>No in-mod settings UI is bound to this flag in v1 (specification
 * "Configuration" section) -- it exists purely so the platform composition
 * root can gate whether the real or {@code Noop*} service set is constructed,
 * matching every other feature's always-{@code true}-by-default config
 * record shape.
 *
 * @param enabled master switch; even when {@code true}, has no effect unless
 *                {@code SteamworksService.isSteamAvailable()}.
 */
public record RichPresenceConfig(boolean enabled) {

    /**
     * The default configuration used when no config file exists yet, or when
     * an existing file fails to parse.
     */
    public static final RichPresenceConfig DEFAULT = new RichPresenceConfig(true);
}
