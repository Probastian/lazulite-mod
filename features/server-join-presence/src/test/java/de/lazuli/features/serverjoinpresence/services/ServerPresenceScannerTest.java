package de.lazuli.features.serverjoinpresence.services;

import de.lazuli.services.steamworks.SteamFriendsGateway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ServerPresenceScannerTest {

    @Test
    void reportsZeroBeforeFirstTick() {
        ServerPresenceScanner scanner = new ServerPresenceScanner(new FakeGateway(), 5);
        assertThat(scanner.friendsOnServer("example.com:25565")).isZero();
    }

    @Test
    void countsFriendsOnTheSameServer() {
        FakeGateway gateway = new FakeGateway();
        gateway.addFriend(10L, ServerConnectStringCodec.encode("example.com", 25565));
        gateway.addFriend(20L, ServerConnectStringCodec.encode("example.com", 25565));
        gateway.addFriend(30L, ServerConnectStringCodec.encode("other.example.com", 25565));
        gateway.addFriend(40L, "In the nether biome"); // status text, not a connect string
        gateway.addFriend(50L, null);                  // no rich presence

        ServerPresenceScanner scanner = new ServerPresenceScanner(gateway, 5);
        scanner.tick();

        assertThat(scanner.friendsOnServer("example.com:25565")).isEqualTo(2);
        assertThat(scanner.friendsOnServer("example.com")).isEqualTo(2); // normalized, default port
        assertThat(scanner.friendsOnServer("other.example.com:25565")).isEqualTo(1);
        assertThat(scanner.friendsOnServer("nowhere.example.com:25565")).isZero();
    }

    @Test
    void excludesAFriendAdvertisingASteamWorldHostingSession() {
        // FR3.3: a Steam-World-Hosting-shaped connect string (a singleplayer
        // session) must never be counted by this feature's own scanner.
        FakeGateway gateway = new FakeGateway();
        gateway.addFriend(10L, "+lazuli_join 76561198000000000");
        gateway.addFriend(20L, ServerConnectStringCodec.encode("example.com", 25565));

        ServerPresenceScanner scanner = new ServerPresenceScanner(gateway, 5);
        scanner.tick();

        assertThat(scanner.friendsOnServer("example.com:25565")).isEqualTo(1);
    }

    @Test
    void rescanReflectsAFriendLeavingTheServer() {
        FakeGateway gateway = new FakeGateway();
        gateway.addFriend(10L, ServerConnectStringCodec.encode("example.com", 25565));

        // interval 0 -> every tick rescans
        ServerPresenceScanner scanner = new ServerPresenceScanner(gateway, 0);
        scanner.tick();
        assertThat(scanner.friendsOnServer("example.com:25565")).isEqualTo(1);

        gateway.setConnect(10L, "");
        scanner.tick();
        assertThat(scanner.friendsOnServer("example.com:25565")).isZero();
    }

    @Test
    void exposesFriendIdentitiesBehindTheCount() {
        // Batch-2 FR-BB4.2: friendSteamIdsOnServer(hostPort) must return
        // exactly the friend ids the scanner's cache already holds for that
        // (normalized) server, matching friendsOnServer's own count.
        FakeGateway gateway = new FakeGateway();
        gateway.addFriend(10L, ServerConnectStringCodec.encode("example.com", 25565));
        gateway.addFriend(20L, ServerConnectStringCodec.encode("example.com", 25565));
        gateway.addFriend(30L, ServerConnectStringCodec.encode("other.example.com", 25565));

        ServerPresenceScanner scanner = new ServerPresenceScanner(gateway, 5);
        scanner.tick();

        assertThat(scanner.friendSteamIdsOnServer("example.com:25565")).containsExactlyInAnyOrder(10L, 20L);
        assertThat(scanner.friendSteamIdsOnServer("example.com")).containsExactlyInAnyOrder(10L, 20L);
        assertThat(scanner.friendSteamIdsOnServer("other.example.com:25565")).containsExactly(30L);
        assertThat(scanner.friendSteamIdsOnServer("nowhere.example.com:25565")).isEmpty();
    }

    @Test
    void requestsRichPresenceForEveryFriendDuringAScan() {
        FakeGateway gateway = new FakeGateway();
        gateway.addFriend(10L, null);
        gateway.addFriend(20L, null);

        new ServerPresenceScanner(gateway, 5).tick();

        assertThat(gateway.requested).containsExactlyInAnyOrder(10L, 20L);
    }

    /**
     * Minimal in-test {@link SteamFriendsGateway} seam: a fixed friend list
     * with per-friend {@code "connect"} Rich Presence values. All other
     * methods return safe defaults.
     */
    private static final class FakeGateway implements SteamFriendsGateway {
        private final List<Long> friends = new ArrayList<>();
        private final Map<Long, String> connectByFriend = new HashMap<>();
        private final List<Long> requested = new ArrayList<>();

        void addFriend(long steamId64, String connectValue) {
            friends.add(steamId64);
            connectByFriend.put(steamId64, connectValue);
        }

        void setConnect(long steamId64, String connectValue) {
            connectByFriend.put(steamId64, connectValue);
        }

        @Override
        public int friendCount() {
            return friends.size();
        }

        @Override
        public long friendSteamId64At(int index) {
            return friends.get(index);
        }

        @Override
        public void requestFriendRichPresence(long steamId64) {
            requested.add(steamId64);
        }

        @Override
        public Optional<String> friendRichPresenceValue(long steamId64, String key) {
            String value = connectByFriend.get(steamId64);
            if (value == null || value.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(value);
        }

        // --- unused surface: safe defaults ---
        @Override public long localSteamId64() { return 0L; }
        @Override public String localPersonaName() { return ""; }
        @Override public int localPersonaStateOrdinal() { return 0; }
        @Override public String friendPersonaName(long steamId64) { return ""; }
        @Override public int friendPersonaStateOrdinal(long steamId64) { return 0; }
        @Override public boolean isDirectFriend(long steamId64) { return false; }
        @Override public boolean friendInGame(long steamId64) { return false; }
        @Override public Optional<String> friendGameConnectHint(long steamId64) { return Optional.empty(); }
        @Override public long friendGameAppId(long steamId64) { return 0L; }
        @Override public boolean setLocalRichPresence(String key, String value) { return false; }
        @Override public void clearLocalRichPresence() { }
        @Override public boolean inviteToGame(long friendSteamId64, String connectString) { return false; }
        @Override public int avatarHandle(long steamId64) { return 0; }
        @Override public Optional<byte[]> avatarRgba(long steamId64) { return Optional.empty(); }
        @Override public boolean isOverlayEnabled() { return false; }
        @Override public void activateOverlayChat(long steamId64) { }
        @Override public void activateOverlayProfile(long steamId64) { }
        @Override public void setJoinRequestedListener(java.util.function.BiConsumer<Long, String> listener) { }
        @Override public Set<Long> drainDirtyAvatars() { return Set.of(); }
    }
}
