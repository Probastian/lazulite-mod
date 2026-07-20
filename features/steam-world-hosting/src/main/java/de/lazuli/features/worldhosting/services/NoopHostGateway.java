package de.lazuli.features.worldhosting.services;

/**
 * A {@link HostGateway}-shaped no-op used whenever Steam is unavailable or this
 * feature's config disables it (FR0.2/FR0.3): no remote peer is ever admitted,
 * since no Steam P2P listener is ever stood up in the disabled state anyway.
 */
public final class NoopHostGateway {

    /**
     * @param friendSteamId64 ignored
     * @return always {@code false} (FR0.2/FR0.3)
     */
    public boolean canJoin(long friendSteamId64) {
        return false;
    }
}
