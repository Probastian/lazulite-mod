package de.lazuli.api.friends;

/**
 * Immutable, plain-JVM snapshot of one Steam friend's state, crossing the
 * Platform/Feature boundary (spec Public API item 1). Uses the raw 64-bit
 * Steam ID rather than any steamworks4j {@code SteamID} type, so {@code api}
 * never depends on the Steamworks binding library -- the same "zero
 * dependency, safe for any layer" property {@code SteamAvailability} already
 * guarantees.
 *
 * <p>Usage example (constructed by {@code FriendsService} on each refresh
 * sweep, consumed by a platform Version Adapter's rendering code):
 * <pre>{@code
 * FriendSummary friend = new FriendSummary(
 *         76561198000000000L, "Steve", 1, 0, false, false, null);
 * }</pre>
 *
 * @param steamId64    the friend's raw 64-bit Steam ID
 * @param personaName  the friend's current persona (display) name
 * @param personaState an ordinal encoding of {@code SteamFriends.PersonaState}
 *                     (Offline/Online/Busy/Away/Snooze/LookingToTrade/
 *                     LookingToPlay/Invisible, in {@code SteamFriends.PersonaState.values()}
 *                     order) -- used only for row ordering/graying (FR1.2),
 *                     never to gate visibility
 * @param avatarHandle the opaque Steamworks image handle last returned by
 *                      {@code getSmallFriendAvatar}, {@code 0} if none yet
 * @param inGame       whether {@code getFriendGamePlayed} currently reports
 *                      this friend as in a game
 * @param joinable     whether this friend's current game session is
 *                      considered joinable (v1: always disabled in the UI
 *                      regardless of this value, FR2.6/FR3.4)
 * @param connectHint  an opaque, implementation-defined connect hint (e.g. an
 *                      IP:port string) derived from {@code FriendGameInfo}, or
 *                      {@code null} if not currently in a game; unused in v1
 *                      (no real invite/join mechanism exists yet, FR3.3/FR3.4)
 */
public record FriendSummary(
        long steamId64,
        String personaName,
        int personaState,
        int avatarHandle,
        boolean inGame,
        boolean joinable,
        String connectHint) {
}
