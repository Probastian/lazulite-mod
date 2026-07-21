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

    /**
     * Maps a {@link JoinGatePolicy} to the concrete {@link LongPredicate}
     * this class enforces (implementation plan Decision 5, FR7.8-FR7.10):
     * {@code NOBODY} always rejects, {@code FRIENDS} delegates to
     * {@code isDirectFriend} unchanged (today's only-ever-shipped behavior),
     * {@code EVERYONE} always accepts.
     *
     * @param policy         which of the three states to enforce
     * @param isDirectFriend {@code gateway::isDirectFriend}-backed lookup,
     *                       used only for {@code FRIENDS}
     * @return a new {@link HostGateway} enforcing that policy
     */
    public static HostGateway forPolicy(JoinGatePolicy policy, LongPredicate isDirectFriend) {
        return switch (policy) {
            case NOBODY -> new HostGateway(id -> false);
            case FRIENDS -> new HostGateway(isDirectFriend);
            case EVERYONE -> new HostGateway(id -> true);
        };
    }
}
