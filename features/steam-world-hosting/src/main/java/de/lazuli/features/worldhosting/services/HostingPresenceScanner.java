package de.lazuli.features.worldhosting.services;

import de.lazuli.api.worldhosting.FriendHostingStatusReader;
import de.lazuli.services.steamworks.SteamFriendsGateway;

import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Bridge 2 (World Hosting &rarr; Friends Sidebar, FR4.2): owns its own
 * rate-limited sweep (same interval-gating shape as {@code FriendsService.tick()})
 * that iterates the shared {@link SteamFriendsGateway}'s friend list, reads each
 * friend's Rich Presence {@code "connect"} value, decodes it with
 * {@link ConnectStringCodec}, and caches the set of friends currently hosting a
 * Lazuli-tunneled world.
 *
 * <p>Implements {@link FriendHostingStatusReader} directly, so the platform can
 * hand its {@code api}-typed reference to the Friends Sidebar's Version Adapter
 * (Decision 4) without either feature importing the other. Reads the shared
 * gateway on its own schedule -- <strong>not</strong> {@code FriendsService}'s
 * own per-sweep cache -- fully decoupling the two features' runtime data paths.
 *
 * <p>Plain-JVM-testable: {@link SteamFriendsGateway} is an interface, easily
 * faked; zero {@code net.minecraft.*} import.
 */
public final class HostingPresenceScanner implements FriendHostingStatusReader {

    /** Valve-reserved Rich Presence key advertising a joinable host (FR4.2). */
    static final String CONNECT_KEY = "connect";

    /** Default scan interval, in seconds (Decision 4). */
    public static final int DEFAULT_SCAN_INTERVAL_SECONDS = 5;

    private final SteamFriendsGateway gateway;
    private final long scanIntervalMillis;

    private volatile Set<Long> hostingFriendIds = Set.of();
    private long lastScanAtMillis = -1L;

    /**
     * @param gateway the shared Steam-friends seam
     */
    public HostingPresenceScanner(SteamFriendsGateway gateway) {
        this(gateway, DEFAULT_SCAN_INTERVAL_SECONDS);
    }

    /**
     * @param gateway             the shared Steam-friends seam
     * @param scanIntervalSeconds how often (seconds) to re-scan the friend list
     */
    public HostingPresenceScanner(SteamFriendsGateway gateway, int scanIntervalSeconds) {
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
        Set<Long> hosting = new HashSet<>();
        int count = gateway.friendCount();
        for (int i = 0; i < count; i++) {
            long steamId64 = gateway.friendSteamId64At(i);
            if (steamId64 == 0L) {
                continue;
            }
            gateway.requestFriendRichPresence(steamId64);
            String connect = gateway.friendRichPresenceValue(steamId64, CONNECT_KEY).orElse(null);
            OptionalLong host = ConnectStringCodec.decode(connect);
            if (host.isPresent()) {
                hosting.add(steamId64);
            }
        }
        this.hostingFriendIds = Set.copyOf(hosting);
    }

    @Override
    public boolean isFriendHosting(long friendSteamId64) {
        return hostingFriendIds.contains(friendSteamId64);
    }
}
