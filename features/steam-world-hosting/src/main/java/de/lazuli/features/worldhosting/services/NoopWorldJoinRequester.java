package de.lazuli.features.worldhosting.services;

import de.lazuli.api.worldhosting.WorldJoinRequester;

/**
 * A {@link WorldJoinRequester} that does nothing -- used whenever Steam is
 * unavailable or this feature's config disables it (FR0.2/FR0.3), so the reused
 * Friends Sidebar "Join game" slot never triggers a connect attempt and
 * {@code WorldHostingBridgeHandoff.require()} never returns {@code null}.
 */
public final class NoopWorldJoinRequester implements WorldJoinRequester {

    @Override
    public void joinHostedWorld(long hostSteamId64) {
        // Intentionally empty -- Steam unavailable / feature disabled.
    }
}
