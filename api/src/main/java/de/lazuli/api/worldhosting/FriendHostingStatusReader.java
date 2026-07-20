package de.lazuli.api.worldhosting;

/**
 * A narrow, boolean-only "is this friend currently hosting a Lazuli-tunneled
 * world" query, exposed as a stable {@code api} contract so the Friends
 * Sidebar's composition-root wiring can enable/disable the reused "Join game"
 * context-menu slot (FR4.1/FR4.2) without a direct Feature&rarr;Feature import
 * (implementation plan Decision 4).
 *
 * <p>Deliberately a boolean query rather than a raw-Rich-Presence-string
 * contract -- it hides this feature's own connect-string format entirely
 * behind its boundary.
 *
 * <p>Usage example (platform-side, deciding a menu slot's enablement):
 * <pre>{@code
 * boolean canJoin = hostingStatusReader.isFriendHosting(friend.steamId64());
 * }</pre>
 */
public interface FriendHostingStatusReader {

    /**
     * @param friendSteamId64 the friend to query
     * @return {@code true} if that friend is currently detected as hosting a
     *         Lazuli-tunneled world (FR4.2)
     */
    boolean isFriendHosting(long friendSteamId64);
}
