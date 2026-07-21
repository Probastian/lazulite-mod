package de.lazuli.api.serverbrowser;

/**
 * The Steam matchmaking server-list source a {@link ServerBrowserSession} is
 * currently querying (FR1.6). Favorites/History/Spectator are out of scope
 * in v1 (spec Non-goals).
 *
 * <p>Usage example:
 * <pre>{@code
 * session.start(ServerBrowserSource.INTERNET, this::onRowsChanged, this::onRefreshComplete);
 * }</pre>
 */
public enum ServerBrowserSource {
    /** {@code SteamMatchmakingServers.requestInternetServerList} -- the default source. */
    INTERNET,
    /** {@code SteamMatchmakingServers.requestLANServerList}. */
    LAN
}
