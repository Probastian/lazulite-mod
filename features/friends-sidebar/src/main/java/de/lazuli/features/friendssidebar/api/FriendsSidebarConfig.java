package de.lazuli.features.friendssidebar.api;

/**
 * Immutable configuration for the Friends Sidebar feature.
 *
 * <p>Backed by a small JSON file (see
 * {@code de.lazuli.features.friendssidebar.config.FriendsSidebarConfigIO}):
 * <pre>{@code
 * {
 *   "enabled": true,
 *   "refreshIntervalSeconds": 5
 * }
 * }</pre>
 *
 * <p>Usage example:
 * <pre>{@code
 * FriendsSidebarConfig config = FriendsSidebarConfig.DEFAULT;
 * if (config.enabled()) {
 *     // safe to construct FriendsService, refreshing every
 *     // config.refreshIntervalSeconds() seconds
 * }
 * }</pre>
 *
 * @param enabled                master switch (FR0.3); even when {@code true},
 *                                has no effect unless
 *                                {@code SteamAvailability.isSteamAvailable()}
 * @param refreshIntervalSeconds how often (in seconds) the friend list/state
 *                                is re-queried (FR1.4); this feature's own
 *                                planning-time addition (implementation
 *                                plan Decision 7), not fixed by the spec
 */
public record FriendsSidebarConfig(boolean enabled, int refreshIntervalSeconds) {

    /**
     * The default configuration used when no config file exists yet, or when
     * an existing file fails to parse.
     */
    public static final FriendsSidebarConfig DEFAULT = new FriendsSidebarConfig(true, 5);
}
