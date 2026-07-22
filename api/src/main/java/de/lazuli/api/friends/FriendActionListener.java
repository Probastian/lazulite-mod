package de.lazuli.api.friends;

/**
 * Row/context-menu click callback surface, handed to a platform Version
 * Adapter so a click on a friend row or one of its context-menu options can
 * call back into feature logic without the platform layer needing to know
 * about {@code SteamFriends} itself (spec Public API item 1).
 *
 * <p>{@link #onInvite(long)} is now a real, reachable implementation
 * (specification-invite-to-game.md); {@link #onJoin(long)} remains an
 * unreachable disabled placeholder in v1, since its menu option still always
 * renders as disabled (FR2.6, FR3.4).
 *
 * <p>Usage example (platform-side context-menu row click handling):
 * <pre>{@code
 * FriendActionListener listener = ...; // typically FriendsService itself
 * listener.onOpenChat(friend.steamId64());
 * }</pre>
 */
public interface FriendActionListener {

    /** Opens Steam's own overlay chat dialog for the given friend (FR3.1). */
    void onOpenChat(long steamId64);

    /** Opens Steam's own overlay profile dialog for the given friend (FR3.2). */
    void onShowProfile(long steamId64);

    /**
     * Sends a real Steam invite for the local player's current hosted world
     * to the given friend, when hosting (specification-invite-to-game.md
     * FR-INV4/FR-INV5). The context-menu "Invite to game" click path itself
     * bypasses this method and calls the {@code WorldInviteSender} bridge
     * directly (mirroring {@link #onJoin(long)}'s own existing bypass); this
     * method remains a real, callable implementation for interface
     * completeness and any future non-context-menu caller.
     */
    void onInvite(long steamId64);

    /**
     * Disabled placeholder in v1 (FR3.4) -- never reachable from the UI,
     * since "Join game" always renders non-actionable.
     */
    void onJoin(long steamId64);
}
