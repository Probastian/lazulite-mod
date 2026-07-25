package de.lazuli.features.serverjoinpresence.api;

/**
 * Immutable configuration for the Server Join Presence feature.
 *
 * <p>Backed by a small JSON file (see
 * {@code de.lazuli.features.serverjoinpresence.config.ServerJoinPresenceConfigIO}):
 * <pre>{@code
 * {
 *   "enabled": true
 * }
 * }</pre>
 *
 * @param enabled master switch (FR0.3); even when {@code true}, has no effect
 *                unless {@code SteamAvailability.isSteamAvailable()}. When
 *                {@code false}, connecting to/from a multiplayer server
 *                behaves identically to Steam being unavailable (FR0.2).
 */
public record ServerJoinPresenceConfig(boolean enabled) {

    /**
     * The default configuration used when no config file exists yet, or when
     * an existing file fails to parse.
     */
    public static final ServerJoinPresenceConfig DEFAULT = new ServerJoinPresenceConfig(true);
}
