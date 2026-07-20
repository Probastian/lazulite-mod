package de.lazuli.features.worldhosting.services;

import java.util.function.LongPredicate;

/**
 * The plain-JVM-testable friend-gate predicate (FR1.3/FR1.5): decides whether a
 * remote {@code SteamID64} attempting a P2P session is allowed to join. Takes a
 * friend-relationship lookup as a constructor-injected {@link LongPredicate}
 * (supplied by the platform composition root as {@code gateway::isDirectFriend})
 * rather than calling {@code SteamFriends} directly -- the same injected-seam
 * shape {@code FriendSidebarStateMachine} already uses relative to
 * {@code FriendsService}. Zero {@code net.minecraft.*}/steamworks4j import.
 *
 * <p>Usage example:
 * <pre>{@code
 * HostGateway gate = new HostGateway(gateway::isDirectFriend);
 * if (gate.canJoin(remoteSteamId64)) { ... accept the P2P session ... }
 * }</pre>
 */
public final class HostGateway {

    private final LongPredicate friendRelationshipLookup;

    /**
     * @param friendRelationshipLookup returns {@code true} iff the given
     *                                 {@code SteamID64} is a direct Steam friend
     *                                 of the local player
     */
    public HostGateway(LongPredicate friendRelationshipLookup) {
        this.friendRelationshipLookup = friendRelationshipLookup;
    }

    /**
     * @param friendSteamId64 the remote {@code SteamID64} requesting to join
     * @return {@code true} iff that {@code SteamID64} is a direct Steam friend
     *         (FR1.3) -- the fixed v1 rule that replaces the prototype's full
     *         {@code JoinPolicy} enum
     */
    public boolean canJoin(long friendSteamId64) {
        return friendRelationshipLookup.test(friendSteamId64);
    }
}
