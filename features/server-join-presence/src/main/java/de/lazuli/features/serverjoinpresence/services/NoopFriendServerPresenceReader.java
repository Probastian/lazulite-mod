package de.lazuli.features.serverjoinpresence.services;

import de.lazuli.api.serverjoinpresence.FriendServerPresenceReader;

/**
 * A {@link FriendServerPresenceReader} that always reports {@code 0} -- used
 * whenever Steam is unavailable or this feature's config disables it
 * (FR0.2/FR0.3), so {@code ServerJoinPresenceBridgeHandoff.require()} never
 * returns {@code null}.
 */
public final class NoopFriendServerPresenceReader implements FriendServerPresenceReader {

    @Override
    public int friendsOnServer(String hostPort) {
        return 0;
    }

    @Override
    public java.util.List<Long> friendSteamIdsOnServer(String hostPort) {
        return java.util.List.of();
    }
}
