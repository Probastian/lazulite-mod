package de.lazuli.features.worldhosting.services;

import de.lazuli.services.steamworks.SteamFriendsGateway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HostingPresenceScannerTest {

    @Test
    void reportsNoFriendHostingBeforeFirstTick() {
        HostingPresenceScanner scanner = new HostingPresenceScanner(new FakeGateway(), 5);
        assertThat(scanner.isFriendHosting(10L)).isFalse();
    }

    @Test
    void detectsAHostingFriendFromConnectString() {
        FakeGateway gateway = new FakeGateway();
        gateway.addFriend(10L, ConnectStringCodec.encode(10L)); // hosting
        gateway.addFriend(20L, "In the nether biome");          // status text, not a connect string
        gateway.addFriend(30L, null);                           // no rich presence

        HostingPresenceScanner scanner = new HostingPresenceScanner(gateway, 5);
        scanner.tick();

        assertThat(scanner.isFriendHosting(10L)).isTrue();
        assertThat(scanner.isFriendHosting(20L)).isFalse();
        assertThat(scanner.isFriendHosting(30L)).isFalse();
        assertThat(scanner.isFriendHosting(999L)).isFalse();
    }

    @Test
    void aFriendMayAdvertiseADifferentHostId() {
        // A connected client relaying a host advertises the *host's* id, not
        // their own -- decode returns whatever the connect string carries.
        FakeGateway gateway = new FakeGateway();
        gateway.addFriend(10L, ConnectStringCodec.encode(555L));

        HostingPresenceScanner scanner = new HostingPresenceScanner(gateway, 5);
        scanner.tick();

        // The scanner keys "is hosting" by the friend whose presence carried a
        // valid connect string, regardless of the embedded host id.
        assertThat(scanner.isFriendHosting(10L)).isTrue();
    }

    @Test
    void rescanReflectsAFriendStoppingHosting() {
        FakeGateway gateway = new FakeGateway();
        gateway.addFriend(10L, ConnectStringCodec.encode(10L));

        // interval 0 -> every tick rescans
        HostingPresenceScanner scanner = new HostingPresenceScanner(gateway, 0);
        scanner.tick();
        assertThat(scanner.isFriendHosting(10L)).isTrue();

        gateway.setConnect(10L, "");
        scanner.tick();
        assertThat(scanner.isFriendHosting(10L)).isFalse();
    }

    @Test
    void requestsRichPresenceForEveryFriendDuringAScan() {
        FakeGateway gateway = new FakeGateway();
        gateway.addFriend(10L, null);
        gateway.addFriend(20L, null);

        new HostingPresenceScanner(gateway, 5).tick();

        assertThat(gateway.requested).containsExactlyInAnyOrder(10L, 20L);
    }

    /**
     * Minimal in-test {@link SteamFriendsGateway} seam: a fixed friend list with
     * per-friend {@code "connect"} Rich Presence values. All other methods
     * return safe defaults.
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
        @Override public boolean setLocalRichPresence(String key, String value) { return false; }
        @Override public void clearLocalRichPresence() { }
        @Override public int avatarHandle(long steamId64) { return 0; }
        @Override public Optional<byte[]> avatarRgba(long steamId64) { return Optional.empty(); }
        @Override public boolean isOverlayEnabled() { return false; }
        @Override public void activateOverlayChat(long steamId64) { }
        @Override public void activateOverlayProfile(long steamId64) { }
        @Override public void setJoinRequestedListener(java.util.function.BiConsumer<Long, String> listener) { }
        @Override public Set<Long> drainDirtyAvatars() { return Set.of(); }
    }
}
