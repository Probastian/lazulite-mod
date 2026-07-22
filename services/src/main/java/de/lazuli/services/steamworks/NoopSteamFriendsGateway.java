package de.lazuli.services.steamworks;

import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * A {@link SteamFriendsGateway} that never touches steamworks4j and reports
 * safe, empty defaults for everything -- constructed whenever
 * {@code SteamAvailability.isSteamAvailable()} is {@code false}, so no
 * {@code SteamFriends}/{@code SteamUtils}/{@code SteamUser} object is ever
 * created (mirrors every other {@code Noop*} in this repo).
 */
public final class NoopSteamFriendsGateway implements SteamFriendsGateway {

    @Override
    public long localSteamId64() {
        return 0L;
    }

    @Override
    public String localPersonaName() {
        return "";
    }

    @Override
    public int localPersonaStateOrdinal() {
        return 0;
    }

    @Override
    public int friendCount() {
        return 0;
    }

    @Override
    public long friendSteamId64At(int index) {
        return 0L;
    }

    @Override
    public String friendPersonaName(long steamId64) {
        return "";
    }

    @Override
    public int friendPersonaStateOrdinal(long steamId64) {
        return 0;
    }

    @Override
    public boolean isDirectFriend(long steamId64) {
        return false;
    }

    @Override
    public boolean friendInGame(long steamId64) {
        return false;
    }

    @Override
    public Optional<String> friendGameConnectHint(long steamId64) {
        return Optional.empty();
    }

    @Override
    public void requestFriendRichPresence(long steamId64) {
        // Intentionally empty -- Steam unavailable.
    }

    @Override
    public Optional<String> friendRichPresenceValue(long steamId64, String key) {
        return Optional.empty();
    }

    @Override
    public boolean setLocalRichPresence(String key, String value) {
        return false;
    }

    @Override
    public void clearLocalRichPresence() {
        // Intentionally empty -- Steam unavailable.
    }

    @Override
    public int avatarHandle(long steamId64) {
        return 0;
    }

    @Override
    public Optional<byte[]> avatarRgba(long steamId64) {
        return Optional.empty();
    }

    @Override
    public boolean isOverlayEnabled() {
        return false;
    }

    @Override
    public void activateOverlayChat(long steamId64) {
        // Intentionally empty -- Steam unavailable.
    }

    @Override
    public void activateOverlayProfile(long steamId64) {
        // Intentionally empty -- Steam unavailable.
    }

    @Override
    public boolean inviteToGame(long friendSteamId64, String connectString) {
        return false;
    }

    @Override
    public void setJoinRequestedListener(BiConsumer<Long, String> listener) {
        // Intentionally empty -- Steam unavailable, no overlay callbacks fire.
    }

    @Override
    public Set<Long> drainDirtyAvatars() {
        return Set.of();
    }
}
