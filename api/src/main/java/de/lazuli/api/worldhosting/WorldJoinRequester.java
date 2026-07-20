package de.lazuli.api.worldhosting;

/**
 * The "join a Steam-hosted world" operation, exposed as a stable {@code api}
 * contract so the Friends Sidebar's composition-root wiring can invoke it
 * (FR4.1/FR4.3) without a direct Feature&rarr;Feature import (implementation
 * plan Decision 4, ADR-0003 shape).
 *
 * <p>Defined here (not in {@code de.lazuli.api.friends}) because
 * {@code steam-world-hosting} owns the join operation itself; the bridge is
 * composition-root wiring only.
 *
 * <p>Usage example (platform-side, from a Friends Sidebar "Join game" click):
 * <pre>{@code
 * WorldJoinRequester requester = WorldHostingBridgeHandoff.require().worldJoinRequester();
 * requester.joinHostedWorld(friend.steamId64());
 * }</pre>
 */
public interface WorldJoinRequester {

    /**
     * Opens a Minecraft connection whose transport is a Steam P2P channel to
     * the given host (FR3.1/FR3.2). Never throws back to the caller.
     *
     * @param hostSteamId64 the host's own {@code SteamID64}
     */
    void joinHostedWorld(long hostSteamId64);
}
