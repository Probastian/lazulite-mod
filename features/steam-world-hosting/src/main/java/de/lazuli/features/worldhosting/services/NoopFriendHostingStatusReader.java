package de.lazuli.features.worldhosting.services;

import de.lazuli.api.worldhosting.FriendHostingStatusReader;

/**
 * A {@link FriendHostingStatusReader} that always reports "not hosting" -- used
 * whenever Steam is unavailable or this feature's config disables it
 * (FR0.2/FR0.3), so the reused Friends Sidebar "Join game" slot stays disabled
 * and {@code WorldHostingBridgeHandoff.require()} never returns {@code null}.
 */
public final class NoopFriendHostingStatusReader implements FriendHostingStatusReader {

    @Override
    public boolean isFriendHosting(long friendSteamId64) {
        return false;
    }
}
