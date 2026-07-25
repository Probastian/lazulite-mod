package de.lazuli.features.serverjoinpresence.services;

import de.lazuli.api.serverjoinpresence.ServerJoinRequester;

/**
 * A {@link ServerJoinRequester} that does nothing -- used whenever Steam is
 * unavailable or this feature's config disables it (FR0.2/FR0.3), so
 * {@code ServerJoinPresenceBridgeHandoff.require()} never returns {@code null}.
 */
public final class NoopServerJoinRequester implements ServerJoinRequester {

    @Override
    public void joinServer(String host, int port) {
        // Intentionally empty -- Steam unavailable / feature disabled.
    }
}
