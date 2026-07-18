package de.lazuli.api.friends;

/**
 * Row/context-menu click callback surface, handed to a platform Version
 * Adapter so a click on a friend row or one of its context-menu options can
 * call back into feature logic without the platform layer needing to know
 * about {@code SteamFriends} itself (spec Public API item 1).
 *
 * <p>In v1, {@link #onInvite(long)}/{@link #onJoin(long)} are unreachable
 * from the UI, since their menu options always render as disabled
 * placeholders (FR2.6, FR3.3, FR3.4) -- the interface still declares all four
 * methods for forward compatibility with a future real invite/join
 * mechanism.
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
     * Disabled placeholder in v1 (FR3.3) -- never reachable from the UI,
     * since "Invite to game" always renders non-actionable.
     */
    void onInvite(long steamId64);

    /**
     * Disabled placeholder in v1 (FR3.4) -- never reachable from the UI,
     * since "Join game" always renders non-actionable.
     */
    void onJoin(long steamId64);
}
