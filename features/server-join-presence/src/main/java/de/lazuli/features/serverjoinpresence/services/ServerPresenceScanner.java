package de.lazuli.features.serverjoinpresence.services;

import de.lazuli.api.serverjoinpresence.FriendServerPresenceReader;
import de.lazuli.services.steamworks.SteamFriendsGateway;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns its own rate-limited sweep (same interval-gating shape as
 * {@code features.worldhosting.services.HostingPresenceScanner.tick()}) that
 * iterates the shared {@link SteamFriendsGateway}'s friend list, reads each
 * friend's Rich Presence {@code "connect"} value, decodes it with
 * {@link ServerConnectStringCodec}, and caches which friends are currently on
 * which server (spec FR3.1).
 *
 * <p>Implements {@link FriendServerPresenceReader} directly, so the platform
 * can hand its {@code api}-typed reference to whatever eventually consumes it
 * (e.g. a future {@code features/main-menu} amendment) without either feature
 * importing the other.
 *
 * <p>A friend whose {@code "connect"} value instead matches
 * {@code features.worldhosting.services.ConnectStringCodec}'s own
 * {@code "+lazuli_join "} format (a singleplayer Steam World Hosting session)
 * simply fails {@link ServerConnectStringCodec#decode(String)} and is never
 * counted here (FR3.3) -- no explicit cross-feature check is needed, since
 * each codec only recognizes its own prefix by construction.
 *
 * <p>Plain-JVM-testable: {@link SteamFriendsGateway} is an interface, easily
 * faked; zero {@code net.minecraft.*} import.
 */
public final class ServerPresenceScanner implements FriendServerPresenceReader {

    /** Valve-reserved Rich Presence key this scanner reads (FR3.1). */
    static final String CONNECT_KEY = "connect";

    /** Default scan interval, in seconds (mirrors HostingPresenceScanner's own default). */
    public static final int DEFAULT_SCAN_INTERVAL_SECONDS = 5;

    private final SteamFriendsGateway gateway;
    private final long scanIntervalMillis;

    private volatile Map<String, Set<Long>> friendsByServer = Map.of();
    private long lastScanAtMillis = -1L;

    /**
     * @param gateway the shared Steam-friends seam
     */
    public ServerPresenceScanner(SteamFriendsGateway gateway) {
        this(gateway, DEFAULT_SCAN_INTERVAL_SECONDS);
    }

    /**
     * @param gateway             the shared Steam-friends seam
     * @param scanIntervalSeconds how often (seconds) to re-scan the friend list
     */
    public ServerPresenceScanner(SteamFriendsGateway gateway, int scanIntervalSeconds) {
        this.gateway = gateway;
        // 0 means "rescan every tick" (used by tests); negatives clamp to 0.
        this.scanIntervalMillis = Math.max(0, scanIntervalSeconds) * 1000L;
    }

    /**
     * Called once per client tick; internally rate-limits its own sweep to the
     * configured interval. Always fast and non-throwing.
     */
    public void tick() {
        long now = System.currentTimeMillis();
        if (lastScanAtMillis >= 0 && now - lastScanAtMillis < scanIntervalMillis) {
            return;
        }
        lastScanAtMillis = now;
        scanNow();
    }

    private void scanNow() {
        Map<String, Set<Long>> byServer = new HashMap<>();
        int count = gateway.friendCount();
        for (int i = 0; i < count; i++) {
            long steamId64 = gateway.friendSteamId64At(i);
            if (steamId64 == 0L) {
                continue;
            }
            gateway.requestFriendRichPresence(steamId64);
            String connect = gateway.friendRichPresenceValue(steamId64, CONNECT_KEY).orElse(null);
            Optional<ServerConnectStringCodec.HostPort> target = ServerConnectStringCodec.decode(connect);
            target.ifPresent(hostPort ->
                    byServer.computeIfAbsent(hostPort.asHostPort(), k -> new HashSet<>()).add(steamId64));
        }
        Map<String, Set<Long>> immutable = new HashMap<>();
        byServer.forEach((server, friends) -> immutable.put(server, Set.copyOf(friends)));
        this.friendsByServer = Map.copyOf(immutable);
    }

    @Override
    public int friendsOnServer(String hostPort) {
        String normalized = ServerConnectStringCodec.normalize(hostPort);
        return friendsByServer.getOrDefault(normalized, Set.of()).size();
    }

    /**
     * Batch-2 FR-BB4.2 (option (a)): exposes the identities already sitting
     * in {@link #friendsByServer}'s cache -- no new scan logic, a pure
     * accessor addition.
     */
    @Override
    public java.util.List<Long> friendSteamIdsOnServer(String hostPort) {
        String normalized = ServerConnectStringCodec.normalize(hostPort);
        return java.util.List.copyOf(friendsByServer.getOrDefault(normalized, Set.of()));
    }
}
