package de.lazuli;

import de.lazuli.api.serverjoinpresence.FriendServerPresenceReader;
import de.lazuli.api.serverjoinpresence.ServerJoinRequester;

/**
 * Narrow, composition-root-scoped hand-off carrying the two {@code api}-typed
 * references {@code server-join-presence} exposes: the
 * {@link ServerJoinRequester} that connects to a real multiplayer server, and
 * the {@link FriendServerPresenceReader} answering "how many friends are on
 * this server." Published by {@code ServerJoinPresenceClientInitializer};
 * intended for a future consumer (e.g. a {@code features/main-menu}
 * amendment wiring {@code ServersPanel}'s per-row friend count, spec
 * Non-goals/Future Extensions -- not consumed by any composition root yet).
 *
 * <p>Both references are always non-null (a {@code Noop} pair is published
 * when the feature is disabled), so {@code require()} never returns
 * {@code null}.
 */
public final class ServerJoinPresenceBridgeHandoff {

    private static volatile ServerJoinRequester joinRequester;
    private static volatile FriendServerPresenceReader presenceReader;

    private ServerJoinPresenceBridgeHandoff() {
    }

    /** Publishes both bridge references; called once by {@code ServerJoinPresenceClientInitializer}. */
    public static void publish(ServerJoinRequester joinRequester, FriendServerPresenceReader presenceReader) {
        ServerJoinPresenceBridgeHandoff.joinRequester = joinRequester;
        ServerJoinPresenceBridgeHandoff.presenceReader = presenceReader;
    }

    /** @return the published {@link ServerJoinRequester}. */
    public static ServerJoinRequester requireJoinRequester() {
        return require(joinRequester);
    }

    /** @return the published {@link FriendServerPresenceReader}. */
    public static FriendServerPresenceReader requirePresenceReader() {
        return require(presenceReader);
    }

    private static <T> T require(T value) {
        if (value == null) {
            throw new IllegalStateException(
                    "ServerJoinPresenceBridgeHandoff.require*() called before ServerJoinPresenceClientInitializer "
                            + "published the bridge -- check this module's fabric.mod.json \"client\" entrypoint "
                            + "order (ServerJoinPresenceClientInitializer must have already run).");
        }
        return value;
    }
}
