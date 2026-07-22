package de.lazuli;

import de.lazuli.api.worldhosting.FriendHostingStatusReader;
import de.lazuli.api.worldhosting.WorldInviteSender;
import de.lazuli.api.worldhosting.WorldJoinRequester;

/**
 * Narrow, composition-root-scoped hand-off carrying the three {@code api}-typed
 * bridge references Steam World Hosting exposes to the Friends Sidebar
 * (Decision 4): the {@link WorldJoinRequester} the reused "Join game" slot
 * calls, the {@link FriendHostingStatusReader} that slot's enablement is
 * gated on, and the {@link WorldInviteSender} the reused "Invite to game" slot
 * calls/is gated on. Published by {@code SteamWorldHostingClientInitializer};
 * {@code require()}d by {@code FriendsSidebarClientInitializer}.
 *
 * <p>Ordering is load-bearing (Risk 2): {@code SteamWorldHostingClientInitializer}
 * must appear <strong>before</strong> {@code FriendsSidebarClientInitializer} in
 * this module's {@code fabric.mod.json} {@code "client"} array. All three
 * references are always non-null (a {@code Noop*} trio is published when the
 * feature is disabled), so {@code require()} never returns {@code null}.
 */
public final class WorldHostingBridgeHandoff {

    private static volatile WorldJoinRequester worldJoinRequester;
    private static volatile FriendHostingStatusReader hostingStatusReader;
    private static volatile WorldInviteSender worldInviteSender;

    private WorldHostingBridgeHandoff() {
    }

    /** Publishes all three bridge references; called once by {@code SteamWorldHostingClientInitializer}. */
    public static void publish(WorldJoinRequester joinRequester, FriendHostingStatusReader statusReader,
            WorldInviteSender inviteSender) {
        worldJoinRequester = joinRequester;
        hostingStatusReader = statusReader;
        worldInviteSender = inviteSender;
    }

    /** @return the published {@link WorldJoinRequester}. */
    public static WorldJoinRequester requireJoinRequester() {
        return require(worldJoinRequester);
    }

    /** @return the published {@link FriendHostingStatusReader}. */
    public static FriendHostingStatusReader requireHostingStatusReader() {
        return require(hostingStatusReader);
    }

    /** @return the published {@link WorldInviteSender}. */
    public static WorldInviteSender requireWorldInviteSender() {
        return require(worldInviteSender);
    }

    private static <T> T require(T value) {
        if (value == null) {
            throw new IllegalStateException(
                    "WorldHostingBridgeHandoff.require*() called before SteamWorldHostingClientInitializer published "
                            + "the bridge -- check this module's fabric.mod.json \"client\" entrypoint order "
                            + "(SteamWorldHostingClientInitializer must precede FriendsSidebarClientInitializer).");
        }
        return value;
    }
}
