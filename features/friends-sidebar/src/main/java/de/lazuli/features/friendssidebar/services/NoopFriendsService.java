package de.lazuli.features.friendssidebar.services;

import de.lazuli.api.friends.FriendSummary;

import java.util.List;
import java.util.Optional;

/**
 * A {@link FriendsDataSource} that never touches steamworks4j and always
 * reports an empty friend list, no-oping every action -- used whenever
 * {@code SteamAvailability.isSteamAvailable()} is {@code false} or this
 * feature's own config disables it (FR0.2/FR0.3), so no
 * {@code SteamFriends}/{@code SteamUtils} object is ever constructed.
 *
 * <p>Usage example:
 * <pre>{@code
 * FriendsDataSource dataSource = new NoopFriendsService();
 * dataSource.tick(); // no-op
 * dataSource.currentFriends(); // always empty
 * }</pre>
 */
public final class NoopFriendsService implements FriendsDataSource {

    @Override
    public void tick() {
        // Intentionally empty -- FR0.2.
    }

    @Override
    public List<FriendSummary> currentFriends() {
        return List.of();
    }

    @Override
    public Optional<byte[]> avatarRgba(long steamId64) {
        return Optional.empty();
    }

    @Override
    public Optional<FriendSummary> localProfile() {
        return Optional.empty();
    }

    @Override
    public Optional<String> richPresenceStatus(long steamId64) {
        return Optional.empty();
    }

    @Override
    public void onOpenChat(long steamId64) {
        // Intentionally empty -- FR0.2.
    }

    @Override
    public void onShowProfile(long steamId64) {
        // Intentionally empty -- FR0.2.
    }

    @Override
    public void onInvite(long steamId64) {
        // Intentionally empty -- FR3.3, and FR0.2.
    }

    @Override
    public void onJoin(long steamId64) {
        // Intentionally empty -- FR3.4, and FR0.2.
    }
}
