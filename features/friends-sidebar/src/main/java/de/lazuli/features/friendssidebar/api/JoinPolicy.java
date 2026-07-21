package de.lazuli.features.friendssidebar.api;

/**
 * Who may join the local player's Steam-P2P-hosted singleplayer world
 * (v1.3 amendment, FR7.1-FR7.13). Lives in this feature's own
 * feature-internal {@code api} sub-package alongside {@link FriendsSidebarConfig}
 * (implementation plan Decision 3) -- this feature owns the config field and
 * the UI, even though {@code features/steam-world-hosting} is the eventual
 * consumer of the resolved predicate (bridged only at composition-root time,
 * never via a direct import in either direction).
 */
public enum JoinPolicy {
    NOBODY,
    FRIENDS,
    EVERYONE
}
