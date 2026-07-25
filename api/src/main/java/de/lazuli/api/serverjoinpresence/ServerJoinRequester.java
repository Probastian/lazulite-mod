package de.lazuli.api.serverjoinpresence;

/**
 * Platform-API contract for "connect to this real multiplayer server"
 * (spec {@code features/server-join-presence/specification.md} FR2.1/FR2.2),
 * the multiplayer-client analogue of
 * {@code de.lazuli.api.worldhosting.WorldJoinRequester}'s singleplayer/Steam
 * World Hosting equivalent.
 *
 * <p>Implemented by the platform composition root's real connect operation
 * (reusing vanilla's own {@code ConnectScreen}/{@code Connection.connect(...)}
 * flow, plan Decision 5) when Steam is available and this feature is enabled,
 * or by a {@code Noop} implementation otherwise (FR0.2/FR0.3) -- callers never
 * need a null-check.
 */
public interface ServerJoinRequester {

    /**
     * Connects the local client to the given real multiplayer server, exactly
     * as if the player had entered this address via Direct Connect.
     *
     * @param host the server's hostname/IP
     * @param port the server's port
     */
    void joinServer(String host, int port);
}
