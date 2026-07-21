package de.lazuli.api.serverbrowser;

/**
 * A single steamworks4j-free row of Steam matchmaking server-list data,
 * crossing the Platform/Feature boundary via {@link ServerBrowserSession}
 * (spec Public API item 1). Only {@code ServerBrowserQuery}
 * (feature-internal) ever translates a real
 * {@code com.codedisaster.steamworks.SteamMatchmakingGameServerItem} into
 * this record.
 *
 * <p>{@code address} is the pre-formatted
 * {@code SteamMatchmakingServerNetAdr.getConnectionAddressString()} result
 * (host:port), ready to hand to a vanilla connect entry point without any
 * further formatting.
 *
 * <p>Usage example (rendering a table row):
 * <pre>{@code
 * ServerBrowserRow row = ...; // from ServerBrowserSession.currentRows()
 * String label = row.serverName() + " (" + row.players() + "/" + row.maxPlayers() + ")";
 * }</pre>
 *
 * @param serverName           the server's display name
 * @param map                  the map/game-mode string ({@code getMap()}, or
 *                             {@code getGameDescription()} if empty)
 * @param players              current player count
 * @param maxPlayers           maximum player count
 * @param ping                 ping in milliseconds, as reported by Steam's
 *                             own list query
 * @param hasPassword          whether the server is password-protected
 * @param isSecure             whether the server is VAC-secured
 * @param address              the resolved connect address, "host:port"
 * @param respondedSuccessfully whether this row's server actually responded
 *                             (FR1.2/FR4.2) -- a row with {@code false} here
 *                             is rendered disabled and must not be joinable
 */
public record ServerBrowserRow(String serverName, String map, int players, int maxPlayers,
                                int ping, boolean hasPassword, boolean isSecure,
                                String address, boolean respondedSuccessfully) {
}
