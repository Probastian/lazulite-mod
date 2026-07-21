package de.lazuli.features.serverbrowser.services;

import com.codedisaster.steamworks.SteamMatchmakingGameServerItem;
import com.codedisaster.steamworks.SteamMatchmakingKeyValuePair;
import com.codedisaster.steamworks.SteamMatchmakingServerListResponse;
import com.codedisaster.steamworks.SteamMatchmakingServers;
import com.codedisaster.steamworks.SteamServerListRequest;

import de.lazuli.api.serverbrowser.ServerBrowserRow;
import de.lazuli.api.serverbrowser.ServerBrowserSource;
import de.lazuli.api.steamworks.SteamAvailability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * The sole {@code com.codedisaster.steamworks.SteamMatchmaking*}-importing
 * class in this feature (NFR3, spec Decision 1) -- wraps
 * {@link SteamMatchmakingServers}/{@link SteamMatchmakingServerListResponse}/
 * {@link SteamServerListRequest} behind a small, callback-free-to-callers
 * surface (spec Public API item 3). Never constructs
 * {@link SteamMatchmakingServers} unless {@link SteamAvailability#isSteamAvailable()}
 * (FR1.5). Every steamworks4j call site is wrapped in try/catch so no
 * uncaught exception reaches the client tick/render thread (NFR2).
 *
 * <p>Usage example:
 * <pre>{@code
 * ServerBrowserQuery query = new ServerBrowserQuery(steamAvailability, steamworksService::steamAppIdAsInt, LOGGER::warn);
 * query.start(ServerBrowserSource.INTERNET, rows -> ..., () -> ...);
 * query.refresh();
 * query.close();
 * }</pre>
 */
public final class ServerBrowserQuery {

    private final SteamAvailability steamAvailability;
    private final IntSupplier appIdSupplier;
    private final Consumer<String> warningLogger;

    private SteamMatchmakingServers matchmakingServers;
    private SteamServerListRequest currentRequest;
    private ServerBrowserSource currentSource;
    private ResponseHandler currentHandler;

    private final Map<Integer, ServerBrowserRow> rowsByIndex = new LinkedHashMap<>();
    private Consumer<List<ServerBrowserRow>> onRowsChanged;
    private Runnable onRefreshComplete;

    // Tracks the last-observed isRefreshing() value so the true->false
    // transition (refresh finished) is detected exactly once per query,
    // triggering pullAllServerDetails() below.
    private Boolean lastRefreshingState;

    public ServerBrowserQuery(SteamAvailability steamAvailability, IntSupplier appIdSupplier, Consumer<String> warningLogger) {
        this.steamAvailability = steamAvailability;
        this.appIdSupplier = appIdSupplier;
        this.warningLogger = warningLogger;
    }

    /**
     * Issues one {@code requestInternetServerList}/{@code requestLANServerList}
     * call for {@code source} (FR1.1/FR1.6). Releases any previously in-flight
     * request first (FR1.4). A no-op if Steam is unavailable (FR1.5).
     */
    public void start(ServerBrowserSource source, Consumer<List<ServerBrowserRow>> onRowsChanged, Runnable onRefreshComplete) {
        this.onRowsChanged = onRowsChanged;
        this.onRefreshComplete = onRefreshComplete;

        close();
        rowsByIndex.clear();
        lastRefreshingState = null;

        if (!steamAvailability.isSteamAvailable()) {
            return;
        }

        try {
            if (matchmakingServers == null) {
                matchmakingServers = new SteamMatchmakingServers();
            }
            currentSource = source;
            currentHandler = new ResponseHandler();
            int appId = appIdSupplier.getAsInt();
            currentRequest = source == ServerBrowserSource.LAN
                    ? matchmakingServers.requestLANServerList(appId, currentHandler)
                    : matchmakingServers.requestInternetServerList(appId, new SteamMatchmakingKeyValuePair[0], currentHandler);
        } catch (RuntimeException e) {
            warn("ServerBrowserQuery.start failed: " + e);
            currentRequest = null;
        }
    }

    /** Re-issues a refresh against the existing request (FR1.3) -- never a new request. */
    public void refresh() {
        if (matchmakingServers == null || currentRequest == null) {
            return;
        }
        try {
            matchmakingServers.refreshQuery(currentRequest);
        } catch (RuntimeException e) {
            warn("ServerBrowserQuery.refresh failed: " + e);
        }
    }

    /** @return whether the current request is refreshing (FR1.3) */
    public boolean isRefreshing() {
        if (matchmakingServers == null || currentRequest == null) {
            return false;
        }
        try {
            boolean refreshing = matchmakingServers.isRefreshing(currentRequest);
            if (lastRefreshingState == null || lastRefreshingState != refreshing) {
                boolean wasRefreshing = Boolean.TRUE.equals(lastRefreshingState);
                lastRefreshingState = refreshing;
                if (wasRefreshing && !refreshing) {
                    // The per-server serverResponded/refreshComplete JNI proxy
                    // callbacks are unreliable in practice (observed: never
                    // fired at all despite a populated getServerCount()) --
                    // pull every server's details directly instead of relying
                    // on them, matching Valve's own documented fallback of
                    // polling getServerDetails(0..getServerCount()) once a
                    // request stops refreshing.
                    pullAllServerDetails(matchmakingServers.getServerCount(currentRequest));
                    if (onRefreshComplete != null) {
                        onRefreshComplete.run();
                    }
                }
            }
            return refreshing;
        } catch (RuntimeException e) {
            warn("ServerBrowserQuery.isRefreshing failed: " + e);
            return false;
        }
    }

    private void pullAllServerDetails(int serverCount) {
        for (int i = 0; i < serverCount; i++) {
            try {
                SteamMatchmakingGameServerItem details = new SteamMatchmakingGameServerItem();
                boolean valid = matchmakingServers.getServerDetails(currentRequest, i, details);
                if (valid) {
                    rowsByIndex.put(i, toRow(details));
                }
            } catch (RuntimeException e) {
                warn("ServerBrowserQuery.pullAllServerDetails failed for index " + i + ": " + e);
            }
        }
        publishRows();
    }

    /**
     * Releases the current request, if any (FR1.4). Idempotent -- safe to
     * call multiple times, or when never started.
     */
    public void close() {
        if (matchmakingServers != null && currentRequest != null) {
            try {
                matchmakingServers.releaseRequest(currentRequest);
            } catch (RuntimeException e) {
                warn("ServerBrowserQuery.close failed to release request: " + e);
            }
        }
        currentRequest = null;
        currentHandler = null;
    }

    private void warn(String message) {
        if (warningLogger != null) {
            warningLogger.accept(message);
        }
    }

    /**
     * Implements the three {@link SteamMatchmakingServerListResponse}
     * callbacks (FR1.2), fired synchronously during
     * {@code SteamAPI.runCallbacks()} on the client tick thread.
     */
    private final class ResponseHandler extends SteamMatchmakingServerListResponse {

        @Override
        public void serverResponded(SteamServerListRequest request, int server) {
            if (request != currentRequest || matchmakingServers == null) {
                return;
            }
            try {
                SteamMatchmakingGameServerItem details = new SteamMatchmakingGameServerItem();
                boolean valid = matchmakingServers.getServerDetails(request, server, details);
                if (!valid) {
                    rowsByIndex.remove(server);
                } else {
                    rowsByIndex.put(server, toRow(details));
                }
                publishRows();
            } catch (RuntimeException e) {
                warn("ServerBrowserQuery.serverResponded failed for index " + server + ": " + e);
            }
        }

        @Override
        public void serverFailedToRespond(SteamServerListRequest request, int server) {
            if (request != currentRequest) {
                return;
            }
            try {
                ServerBrowserRow existing = rowsByIndex.get(server);
                if (existing != null) {
                    rowsByIndex.put(server, new ServerBrowserRow(existing.serverName(), existing.map(),
                            existing.players(), existing.maxPlayers(), existing.ping(), existing.hasPassword(),
                            existing.isSecure(), existing.address(), false));
                } else {
                    rowsByIndex.put(server, new ServerBrowserRow("(no response)", "", 0, 0, 0, false, false, "", false));
                }
                publishRows();
            } catch (RuntimeException e) {
                warn("ServerBrowserQuery.serverFailedToRespond failed for index " + server + ": " + e);
            }
        }

        @Override
        public void refreshComplete(SteamServerListRequest request, Response response) {
            if (request != currentRequest) {
                return;
            }
            if (onRefreshComplete != null) {
                onRefreshComplete.run();
            }
        }
    }

    private void publishRows() {
        if (onRowsChanged != null) {
            onRowsChanged.accept(List.copyOf(rowsByIndex.values()));
        }
    }

    private static ServerBrowserRow toRow(SteamMatchmakingGameServerItem details) {
        String map = details.getMap();
        if (map == null || map.isEmpty()) {
            map = details.getGameDescription();
        }
        String address = details.getNetAdr() != null ? details.getNetAdr().getConnectionAddressString() : "";
        return new ServerBrowserRow(
                details.getServerName(),
                map == null ? "" : map,
                details.getPlayers(),
                details.getMaxPlayers(),
                details.getPing(),
                details.hasPassword(),
                details.isSecure(),
                address,
                details.hadSuccessfulResponse());
    }
}
