package de.lazuli.api.worldhosting;

/**
 * The "invite a friend to my currently-hosted Steam World Hosting session"
 * operation, exposed as a stable {@code api} contract so the Friends
 * Sidebar's composition-root wiring can invoke it (spec
 * {@code specification-invite-to-game.md} FR-INV1/FR-INV4) without a direct
 * Feature&rarr;Feature import (ADR-0003 shape, mirroring
 * {@link WorldJoinRequester}/{@link FriendHostingStatusReader}).
 *
 * <p>Defined here (not in {@code de.lazuli.api.friends}) because
 * {@code steam-world-hosting} owns both the hosting-state truth and the real
 * Steamworks invite call; the Friends Sidebar only ever consumes it through
 * this contract.
 *
 * <p>Usage example (platform-side, from a Friends Sidebar "Invite to game"
 * click):
 * <pre>{@code
 * WorldInviteSender sender = WorldHostingBridgeHandoff.requireWorldInviteSender();
 * if (sender.isHosting()) {
 *     boolean ok = sender.inviteFriend(friend.steamId64());
 * }
 * }</pre>
 */
public interface WorldInviteSender {

    /**
     * @return true if the local player currently has an active hosted session
     *         AND the current join policy is not NOBODY.
     */
    boolean isHosting();

    /**
     * Sends a real Steam invite for the current hosted session to the given
     * friend. No-ops (returns {@code false}) if {@code !isHosting()}. Never
     * throws.
     *
     * @param friendSteamId64 the friend to invite
     * @return {@code true} on success
     */
    boolean inviteFriend(long friendSteamId64);
}
