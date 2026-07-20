package de.lazuli;

import de.lazuli.api.worldhosting.FriendHostingStatusReader;
import de.lazuli.api.worldhosting.WorldJoinRequester;

/**
 * Narrow, composition-root-scoped hand-off carrying the two {@code api}-typed
 * bridge references Steam World Hosting exposes to the Friends Sidebar
 * (Decision 4): the {@link WorldJoinRequester} the reused "Join game" slot
 * calls, and the {@link FriendHostingStatusReader} that slot's enablement is
 * gated on. Published by {@code SteamWorldHostingClientInitializer};
 * {@code require()}d by {@code FriendsSidebarClientInitializer}.
 *
 * <p>Ordering is load-bearing (Risk 2): {@code SteamWorldHostingClientInitializer}
 * must appear <strong>before</strong> {@code FriendsSidebarClientInitializer} in
 * this module's {@code fabric.mod.json} {@code "client"} array. Both references
 * are always non-null (a {@code Noop*} pair is published when the feature is
 * disabled), so {@code require()} never returns {@code null}.
 */
public final class WorldHostingBridgeHandoff {

    private static volatile WorldJoinRequester worldJoinRequester;
    private static volatile FriendHostingStatusReader hostingStatusReader;

    private WorldHostingBridgeHandoff() {
    }

    /** Publishes both bridge references; called once by {@code SteamWorldHostingClientInitializer}. */
    public static void publish(WorldJoinRequester joinRequester, FriendHostingStatusReader statusReader) {
        worldJoinRequester = joinRequester;
        hostingStatusReader = statusReader;
    }

    /** @return the published {@link WorldJoinRequester}. */
    public static WorldJoinRequester requireJoinRequester() {
        return require(worldJoinRequester);
    }

    /** @return the published {@link FriendHostingStatusReader}. */
    public static FriendHostingStatusReader requireHostingStatusReader() {
        return require(hostingStatusReader);
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
