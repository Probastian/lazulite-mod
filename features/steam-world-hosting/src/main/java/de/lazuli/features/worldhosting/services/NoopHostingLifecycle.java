package de.lazuli.features.worldhosting.services;

import de.lazuli.api.worldhosting.HostedWorldStatus;

/**
 * A {@link HostingLifecycle}-shaped no-op used whenever Steam is unavailable or
 * this feature's config disables it (FR0.2/FR0.3): never sets or clears any
 * Rich Presence, always reports "not hosting". The world hosts normally as
 * vanilla, with no Steam tunnel.
 */
public final class NoopHostingLifecycle {

    /** No-op (FR0.2/FR0.3). */
    public void start() {
        // Intentionally empty.
    }

    /** No-op (FR0.2/FR0.3). */
    public void stop() {
        // Intentionally empty.
    }

    /** @return always {@link HostedWorldStatus#NOT_HOSTING}. */
    public HostedWorldStatus currentStatus() {
        return HostedWorldStatus.NOT_HOSTING;
    }
}
