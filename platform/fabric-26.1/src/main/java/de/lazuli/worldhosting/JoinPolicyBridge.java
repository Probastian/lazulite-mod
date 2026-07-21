package de.lazuli.worldhosting;

import de.lazuli.features.friendssidebar.api.JoinPolicy;
import de.lazuli.features.worldhosting.services.JoinGatePolicy;

/**
 * Platform-layer glue translating {@code features/friends-sidebar}'s
 * {@link JoinPolicy} into {@code features/steam-world-hosting}'s own
 * {@link JoinGatePolicy} (v1.3 amendment, implementation plan Decision 5) --
 * necessarily imports both features' types (ADR-0003), same license
 * {@code SteamWorldHostingClientInitializer} already exercises for
 * {@code WorldJoinRequester}/{@code FriendHostingStatusReader}. Neither
 * feature imports the other's types directly.
 */
public final class JoinPolicyBridge {

    private JoinPolicyBridge() {
    }

    public static JoinGatePolicy toGatePolicy(JoinPolicy policy) {
        return switch (policy) {
            case NOBODY -> JoinGatePolicy.NOBODY;
            case FRIENDS -> JoinGatePolicy.FRIENDS;
            case EVERYONE -> JoinGatePolicy.EVERYONE;
        };
    }
}
