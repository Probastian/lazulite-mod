package de.lazuli.api.serverjoinpresence;

/**
 * Platform-API contract answering "how many of the local player's Steam
 * friends are currently on this multiplayer server" (spec
 * {@code features/server-join-presence/specification.md} FR3.1-FR3.3).
 *
 * <p>Backed by a rate-limited scan of the local player's friends' own Rich
 * Presence {@code "connect"} values, decoded with this feature's own
 * connect-string codec -- a friend whose value instead matches Steam World
 * Hosting's own connect-string shape (a singleplayer session) is never
 * counted here (FR3.3).
 *
 * <p>Implemented by the platform composition root's real scanner when Steam
 * is available and this feature is enabled, or by a {@code Noop}
 * implementation (always returns {@code 0}) otherwise (FR0.2/FR0.3) --
 * callers never need a null-check.
 *
 * <p>This is the contract {@code features/main-menu}'s {@code ServersPanel}
 * is expected to eventually consume for a per-row friend-count display (spec
 * Non-goals: that consuming UI wiring is deferred to a follow-up pass, not
 * built alongside this contract).
 */
public interface FriendServerPresenceReader {

    /**
     * @param hostPort a server address in {@code "host:port"} form (or a bare
     *                 {@code "host"}, which is normalized to vanilla's own
     *                 default port before matching)
     * @return the number of the local player's direct Steam friends currently
     *         reporting (via Rich Presence) that they are connected to that
     *         same server; {@code 0} if none, if Steam is unavailable, or if
     *         this feature is disabled
     */
    int friendsOnServer(String hostPort);

    /**
     * Batch-2 FR-BB4.2 (option (a)): the specific Steam64 identities behind
     * {@link #friendsOnServer(String)}'s count, for rendering per-friend
     * avatars (e.g. {@code features/main-menu}'s {@code ServersPanel}) rather
     * than just a number. This list's size may occasionally lag one tick
     * behind {@link #friendsOnServer(String)}'s own count (both are reads of
     * the same periodically-rescanned cache) -- callers must always treat
     * {@link #friendsOnServer(String)} as the authoritative count (e.g. for a
     * "+N" overflow badge) and this list only as "which identities to render
     * for however many of the first slots it can fill."
     *
     * @param hostPort a server address in {@code "host:port"} form (or a bare
     *                 {@code "host"}, normalized the same way as
     *                 {@link #friendsOnServer(String)})
     * @return the Steam64 ids of friends currently reporting that they are
     *         connected to that server; empty if none, if Steam is
     *         unavailable, or if this feature is disabled
     */
    java.util.List<Long> friendSteamIdsOnServer(String hostPort);
}
